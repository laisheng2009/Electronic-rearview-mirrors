package com.byd.extend;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Classifies rescue-button callback values without synthesizing Android key events. */
final class RescueButtonGesturePolicy {
    static final long DOUBLE_WINDOW_MS = 800L;

    static final class Assignment {
        final CameraButtonBindings.Action action;
        final CameraButtonBindings.Binding binding;

        Assignment(CameraButtonBindings.Action action, CameraButtonBindings.Binding binding) {
            this.action = action;
            this.binding = binding;
        }
    }

    static final class Result {
        CameraButtonBindings.Action action;
        CameraButtonBindings.Source source;
        CameraButtonBindings.Press press;
        boolean hold;
    }

    private final EnumMap<CameraButtonBindings.Source, PendingSingle> pending =
            new EnumMap<>(CameraButtonBindings.Source.class);
    private EnumMap<CameraButtonBindings.Action, CameraButtonBindings.Binding> configuration =
            new EnumMap<>(CameraButtonBindings.Action.class);
    private boolean hasConfiguration;

    Result onEvent(int value, long monotonicMs, List<Assignment> assignments) {
        syncConfiguration(assignments);
        Result result = new Result();
        CameraButtonBindings.Source source = sourceFor(value);
        if (source == null) {
            pending.clear();
            return result;
        }

        if (value == 1 || value == 2) {
            pending.remove(source);
            result.source = source;
            result.press = CameraButtonBindings.Press.Hold;
            result.hold = true;
            return result;
        }

        PendingSingle first = pending.remove(source);
        if (first != null && monotonicMs >= first.receivedMs
                && monotonicMs - first.receivedMs <= DOUBLE_WINDOW_MS) {
            result.source = source;
            result.press = CameraButtonBindings.Press.Double;
            result.action = actionFor(source, CameraButtonBindings.Press.Double);
            return result;
        }

        if (first != null) {
            result.source = source;
            result.press = CameraButtonBindings.Press.Single;
            result.action = actionFor(source, CameraButtonBindings.Press.Single);
        }
        if (hasAction(source, CameraButtonBindings.Press.Double)) {
            pending.put(source, new PendingSingle(monotonicMs));
        } else if (first == null) {
            result.source = source;
            result.press = CameraButtonBindings.Press.Single;
            result.action = actionFor(source, CameraButtonBindings.Press.Single);
        }
        return result;
    }

    Result advance(long monotonicMs, List<Assignment> assignments) {
        syncConfiguration(assignments);
        CameraButtonBindings.Source dueSource = null;
        PendingSingle earliest = null;
        for (Map.Entry<CameraButtonBindings.Source, PendingSingle> entry : pending.entrySet()) {
            PendingSingle value = entry.getValue();
            if (monotonicMs > value.receivedMs + DOUBLE_WINDOW_MS
                    && (earliest == null || value.receivedMs < earliest.receivedMs)) {
                dueSource = entry.getKey();
                earliest = value;
            }
        }
        Result result = new Result();
        if (dueSource != null) {
            pending.remove(dueSource);
            result.source = dueSource;
            result.press = CameraButtonBindings.Press.Single;
            result.action = actionFor(dueSource, CameraButtonBindings.Press.Single);
        }
        return result;
    }

    long nextDeadline() {
        long deadline = Long.MAX_VALUE;
        for (PendingSingle value : pending.values()) {
            deadline = Math.min(deadline, value.receivedMs + DOUBLE_WINDOW_MS + 1L);
        }
        return deadline;
    }

    void reset() {
        pending.clear();
        configuration.clear();
        hasConfiguration = false;
    }

    private void syncConfiguration(List<Assignment> assignments) {
        EnumMap<CameraButtonBindings.Action, CameraButtonBindings.Binding> current =
                new EnumMap<>(CameraButtonBindings.Action.class);
        if (assignments != null) {
            for (Assignment assignment : assignments) {
                if (assignment != null && assignment.action != null && assignment.binding != null) {
                    current.put(assignment.action, assignment.binding);
                }
            }
        }
        if (!hasConfiguration || !configuration.equals(current)) pending.clear();
        configuration = current;
        hasConfiguration = true;
    }

    private boolean hasAction(CameraButtonBindings.Source source,
            CameraButtonBindings.Press press) {
        for (Map.Entry<CameraButtonBindings.Action, CameraButtonBindings.Binding> entry
                : configuration.entrySet()) {
            CameraButtonBindings.Binding binding = entry.getValue();
            if (binding.isAssigned() && binding.source == source && binding.press == press
                    && CameraButtonBindings.supports(entry.getKey(), source, press)) return true;
        }
        return false;
    }

    private CameraButtonBindings.Action actionFor(CameraButtonBindings.Source source,
            CameraButtonBindings.Press press) {
        CameraButtonBindings.Action found = null;
        for (Map.Entry<CameraButtonBindings.Action, CameraButtonBindings.Binding> entry
                : configuration.entrySet()) {
            CameraButtonBindings.Binding binding = entry.getValue();
            if (!binding.isAssigned() || binding.source != source || binding.press != press
                    || !CameraButtonBindings.supports(entry.getKey(), source, press)) continue;
            if (found != null) return null;
            found = entry.getKey();
        }
        return found;
    }

    private static CameraButtonBindings.Source sourceFor(int value) {
        if (value == 1 || value == 3) return CameraButtonBindings.Source.Sos;
        if (value == 2 || value == 4) return CameraButtonBindings.Source.Help;
        return null;
    }

    private static final class PendingSingle {
        final long receivedMs;

        PendingSingle(long receivedMs) {
            this.receivedMs = receivedMs;
        }
    }
}
