package android.media;

/** OEM-only focus getter, absent from AOSP; no audio is produced by the host test. */
public final class BehaviorAudioManager extends AudioManager {
    public String getCurrentAudioFocusPackage() { return ""; }
}
