package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.byd.extend.ui.motion.MotionFullscreenActivity;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** App-owned event/recording coordinator; camera ownership remains in the existing shell host. */
public final class MotionRuntime implements AutoCloseable {
    private static volatile MotionRuntime active;
    private final Context context;
    private final CameraRuntimeClient cameras;
    private final Consumer<String> events;
    private final HandlerThread thread = new HandlerThread("motion-runtime");
    private final Handler handler;
    private final AvasTelemetryController telemetry;
    private final SharedPreferences screenPrefs, recordingPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs,key) -> refresh();
    private MotionConfig config;
    private MotionDetectorClient detector;
    private MotionVideoRecorder recorder;
    private MotionGpuComposer composer;
    private String session;
    private PowerManager.WakeLock wakeLock;
    private int power=-1, lock=-1, requestId, recordingGeneration;
    private boolean telemetryActive, eligible, observing, warning, closed, stopping;
    private long suppressedUntil, lastDetectionLogMs;
    private int lastDetectionLevel;
    private final Runnable arm = this::startSession;
    private final Runnable warningTimeout = () -> finishWarning(false);
    private final Runnable tailFinished = () -> stopSession("recording_tail_finished");

    MotionRuntime(Context context, CameraRuntimeClient cameras, Consumer<String> events) {
        this.context=context.getApplicationContext(); this.cameras=cameras; this.events=events;
        thread.start(); handler=new Handler(thread.getLooper());
        screenPrefs=context.getSharedPreferences(MotionConfig.SCREEN_PREFS,Context.MODE_PRIVATE);
        recordingPrefs=context.getSharedPreferences(MotionConfig.RECORDING_PREFS,Context.MODE_PRIVATE);
        telemetry=new AvasTelemetryController(SystemClock::elapsedRealtime,
                new AvasTelemetryController.Executor() {
                    @Override public void execute(Runnable task) {
                        if (Looper.myLooper()==handler.getLooper()) task.run();
                        else handler.post(task);
                    }
                    @Override public AvasTelemetryController.Cancellable schedule(Runnable task,long delay) {
                        handler.postDelayed(task,delay); return () -> handler.removeCallbacks(task);
                    }
                },new AvasVehicleTelemetryTransport(this.context),new AvasTelemetryController.Sink() {
                    @Override public boolean powerOnSuppressionEligible() { return false; }
                    @Override public boolean powerOffSuppressionEligible() { return false; }
                    @Override public void onProfile(String ignored) { }
                    @Override public void onSuppressed(String profile,String by,long delta) { }
                    @Override public void onRawPower(int value,boolean baseline) {
                        power=AvasEventPolicy.normalizedPower(value); evaluate();
                    }
                    @Override public void onRawLock(int value,boolean baseline) { lock=value; evaluate(); }
                    @Override public void log(String kind,Object... fields) { event("motion_"+kind,fields); }
                },new AvasEventPolicy());
        screenPrefs.registerOnSharedPreferenceChangeListener(changed);
        recordingPrefs.registerOnSharedPreferenceChangeListener(changed);
        active=this;
        refresh();
    }

    /** Called by the UI after persistent edits; does not issue or replace an ADB key. */
    public static void settingsChanged(Context context) {
        MotionConfig current=MotionConfig.read(context);
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
                .putBoolean("motion_monitoring_enabled",current.anyEnabled()).apply();
        CameraHelperService.startPersistent(context,"motion_settings");
        MotionRuntime runtime=active;
        if(runtime!=null) runtime.refresh();
    }

    void refresh() {
        handler.post(() -> {
            if(closed) return;
            MotionConfig next=MotionConfig.read(context);
            boolean modified=config==null || !config.toJson().equals(next.toJson());
            config=next;
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
                    .putBoolean("motion_monitoring_enabled",config.anyEnabled()).apply();
            if(!config.anyEnabled()) {
                eligible=false; handler.removeCallbacks(arm); finishWarning(false);
                stopSession("settings_disabled");
                if(telemetryActive) { telemetryActive=false; telemetry.deactivate(); }
                return;
            }
            if(!telemetryActive) { telemetryActive=true; telemetry.activate(); }
            if(modified && session!=null) { finishWarning(false); stopSession("settings_changed"); eligible=false; }
            if(session!=null && detector!=null && (detector.workerBinder()==null
                    || !detector.workerBinder().isBinderAlive())) {
                stopSession("worker_disconnected"); eligible=false;
            }
            evaluate();
        });
    }

    private boolean conditions() {
        return !closed && config!=null && config.eligible(power,lock)
                && !GuardRecovery.isUserShutdownActive(context);
    }

    private void evaluate() {
        if(closed) return;
        boolean next=conditions();
        if(next==eligible) return;
        eligible=next;
        handler.removeCallbacks(arm);
        if(next) {
            holdCpu(true);
            event("motion_arming","power",power,"lock",lock,"delay_ms",2500);
            handler.postDelayed(arm,2500);
        } else {
            event("motion_disarmed","power",power,"lock",lock);
            finishWarning(false);
            if(detector!=null && session!=null) detector.setEligible(session,false);
            observing=false;
            if(recorder!=null && recorder.isRecording()) {
                long tail=SystemClock.elapsedRealtime()+config.afterSeconds*1000L;
                recorder.finishAfter(tail);
                handler.removeCallbacks(tailFinished);
                handler.postAtTime(tailFinished,SystemClock.uptimeMillis()+config.afterSeconds*1000L);
            } else stopSession("conditions_ended");
        }
    }

    private void startSession() {
        if(!conditions()) return;
        // A new eligible interval supersedes the old tail without replaying old detections.
        if(session!=null) stopSession("new_eligible_interval");
        if(!cameras.connect() || LocalAdbClient.AUTHORIZATION.pending()) {
            eligible=false;
            holdCpu(false);
            event("motion_waiting","reason","authorized_camera_runtime_unavailable");
            return;
        }
        String id=UUID.randomUUID().toString().replace("-", "");
        holdCpu(true);
        session=id; observing=false; suppressedUntil=0; lastDetectionLogMs=0; lastDetectionLevel=0;
        event("motion_session_started","boot_count",android.provider.Settings.Global.getInt(
                context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1),
                "power",power,"lock",lock,"config",config.toJson());
        detector=new MotionDetectorClient(context);
        detector.start(id,new MotionDetectorClient.Listener() {
            @Override public void onPrepared(IBinder worker) { handler.post(() -> {
                if(!current(id) || !conditions()) return;
                if(detector.authorizeCameraSession(id,worker)) detector.setEligible(id,true);
                else failed(id,"camera_authorize","session authorization rejected");
            }); }
            @Override public void onStatus(String state,String detail,long elapsed) { handler.post(() -> {
                if(!current(id)) return;
                event("motion_detector_state","state",state,"detail",detail,"elapsed_ms",elapsed);
                if("observing".equals(state) && conditions() && !observing) {
                    observing=true;
                    if(config.recordingEnabled) startRecording(id);
                }
            }); }
            @Override public void onDetection(int level,long elapsed,int raw,int nativeState,
                    long frame,long frameTime,long until) { handler.post(() -> {
                if(!current(id) || !conditions() || !observing) return;
                if(level!=lastDetectionLevel || elapsed-lastDetectionLogMs>=1000) {
                    event("motion_detected","level",level,"raw",raw,"native_state",nativeState,
                            "frame",frame,"frame_timestamp_ns",frameTime,"elapsed_ms",elapsed);
                    lastDetectionLogMs=elapsed; lastDetectionLevel=level;
                }
                if(recorder!=null) recorder.onDetection(level,elapsed);
                if(!config.warningEnabled) return;
                long now=SystemClock.elapsedRealtime();
                if(now<suppressedUntil) { suppressedUntil=now+10000; return; }
                handler.removeCallbacks(warningTimeout); handler.postDelayed(warningTimeout,10000);
                if(!warning) { warning=true; detector.wakeForWarning(id); }
            }); }
            @Override public void onError(String stage,String detail,long elapsed) {
                handler.post(() -> failed(id,stage,detail));
            }
            @Override public void onScreenState(String state,String detail,long elapsed) { handler.post(() -> {
                if(!current(id)) return;
                event("motion_screen_state","state",state,"detail",detail,"elapsed_ms",elapsed);
                if("wake_requested".equals(state) && warning && conditions()) {
                    try { MotionFullscreenActivity.show(context,id); }
                    catch(RuntimeException failure) {
                        event("motion_warning_error","stage","open_window","error",failure.toString());
                        finishWarning(false);
                    }
                }
                else if(state.endsWith("uncertain") || "off_session_lost".equals(state)) finishWarning(true);
            }); }
        });
    }

    private void startRecording(String id) {
        if(!current(id) || recorder!=null) return;
        try {
            int generation=++recordingGeneration;
            recorder=new MotionVideoRecorder(context,detail -> {
                event("motion_recorder","detail",detail);
                if(detail.startsWith("video_encoder_failed:") || "stopped:encoder_ended".equals(detail))
                    handler.post(() -> {
                        if(current(id) && generation==recordingGeneration) stopRecording("encoder_failed");
                    });
            });
            recorder.startBuffering(config);
            if(!recorder.isBuffering() || recorder.inputSurface()==null)
                throw new IllegalStateException("hardware encoder input unavailable");
            composer=new MotionGpuComposer(recorder.inputSurface(),recorder.width(),recorder.height(),
                    config.cameraOrder,error -> handler.post(() -> {
                        if(!current(id) || generation!=recordingGeneration) return;
                        event("motion_recording_error","stage","video_gpu","error",error);
                        stopRecording("video_gpu_failed");
                    }));
            requestId=requestId==Integer.MAX_VALUE?1:requestId+1;
            if(!cameras.motionCommand(CameraRuntimeHost.MOTION_RECORD_ATTACH,id,null,composer.inputs(),requestId))
                throw new IllegalStateException("motion GPU inputs not attached");
            event("motion_buffering","width",recorder.width(),"height",recorder.height(),
                    "before_s",config.beforeSeconds,"after_s",config.afterSeconds);
        } catch(Exception failure) {
            event("motion_recording_error","stage","start","error",failure.toString());
            stopRecording("start_failed");
        }
    }

    public static void warningWindowReady(Context context,String id) {
        MotionRuntime runtime=active;
        if(runtime!=null) runtime.handler.post(() -> {
            if(runtime.current(id) && runtime.warning && runtime.conditions())
                runtime.detector.warningWindowReady(id);
        });
    }

    public static void dismissWarning(Context context,String id,boolean userAction) {
        MotionRuntime runtime=active;
        if(runtime!=null) runtime.handler.post(() -> {
            if(runtime.current(id)) runtime.finishWarning(userAction,userAction);
        });
    }

    private void finishWarning(boolean userAction) {
        finishWarning(userAction,false);
    }

    private void finishWarning(boolean userAction,boolean alreadyClosed) {
        handler.removeCallbacks(warningTimeout);
        if(!warning || session==null || detector==null) return;
        warning=false;
        if(userAction) {
            suppressedUntil=SystemClock.elapsedRealtime()+10000;
            detector.markUserTakeover(session);
        }
        if(!alreadyClosed) MotionFullscreenActivity.dismiss(context,session);
        detector.finishWarning(session,!userAction && power==0);
        event("motion_warning_finished","user",userAction,"restore_allowed",!userAction && power==0);
    }

    private void failed(String id,String stage,String detail) {
        if(!current(id) || stopping) return;
        event("motion_error","stage",stage,"error",detail);
        finishWarning(true);
        stopSession("runtime_error");
        eligible=false;
    }

    private void stopRecording(String reason) {
        recordingGeneration++;
        if(session!=null && composer!=null)
            cameras.motionCommand(CameraRuntimeHost.MOTION_RECORD_DETACH,session,null,null,0);
        if(composer!=null) { composer.close(); composer=null; }
        if(recorder!=null) {
            try { recorder.stop(reason); recorder.close(); }
            catch(Exception error) { event("motion_recording_error","stage","stop","error",error.toString()); }
            recorder=null;
        }
    }

    private void stopSession(String reason) {
        if(stopping) return;
        stopping=true;
        handler.removeCallbacks(tailFinished);
        stopRecording(reason);
        if(detector!=null) { detector.close(); detector=null; }
        if(session!=null) event("motion_session_stopped","reason",reason);
        session=null; observing=false; stopping=false;
        holdCpu(false);
    }

    private void holdCpu(boolean required) {
        try {
            if(required && wakeLock==null) {
                PowerManager manager=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                if(manager==null) throw new IllegalStateException("PowerManager unavailable");
                wakeLock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"com.byd.extend:motion");
                wakeLock.setReferenceCounted(false);
            }
            if(required && !wakeLock.isHeld()) wakeLock.acquire();
            else if(!required && wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        } catch(RuntimeException error) { event("motion_wake_lock_error","error",error.toString()); }
    }

    void acceptCameraEvent(String line) {
        if(line==null || !(line.contains("\"camera_producer_closed\"")
                || line.contains("\"helper_death\"") || line.contains("\"helper_ping_failed\""))) return;
        handler.post(() -> {
            if(session!=null && !stopping) failed(session,"camera_session_lost",line);
        });
    }

    private boolean current(String id) { return !closed && id!=null && id.equals(session); }
    private void event(String kind,Object... fields) {
        try {
            JSONObject json=new JSONObject().put("kind",kind).put("elapsed_ms",SystemClock.elapsedRealtime())
                    .put("wall_ms",System.currentTimeMillis())
                    .put("session_id",session==null?"":session);
            for(int i=0;i+1<fields.length;i+=2) json.put(String.valueOf(fields[i]),fields[i+1]);
            events.accept(json.toString());
        } catch(Exception ignored) { android.util.Log.w("BydMotion",kind); }
    }

    @Override public void close() {
        screenPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        recordingPrefs.unregisterOnSharedPreferenceChangeListener(changed);
        FutureTask<Void> task=new FutureTask<>(() -> {
            handler.removeCallbacksAndMessages(null);
            finishWarning(false); closed=true; eligible=false;
            telemetry.close(); stopSession("runtime_stopped");
            if(active==this) active=null;
            return null;
        });
        handler.post(task);
        try { task.get(10,TimeUnit.SECONDS); }
        catch(Exception failure) { event("motion_close_error","error",failure.toString()); }
        thread.quitSafely();
    }
}
