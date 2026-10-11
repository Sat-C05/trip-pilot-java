package com.trippilot.model;

import com.trippilot.config.TripPilotProperties;
import com.trippilot.research.HarnessProperties;
import org.springframework.stereotype.Component;

@Component
public class ModelConfigurationFactory {
    private final TripPilotProperties properties;
    private final ModelRuntimeProperties runtime;
    private final HarnessProperties harness;
    public ModelConfigurationFactory(TripPilotProperties properties, ModelRuntimeProperties runtime, HarnessProperties harness) {
        this.properties = properties; this.runtime = runtime; this.harness = harness;
    }
    /** Capture once at job admission; adapters consume this value rather than live settings. */
    public InferenceConfiguration snapshot() {
        var model = properties.model();
        return new InferenceConfiguration(new ModelSnapshot(runtime.provider(), model.modelId(),
                "gemini".equals(runtime.provider()) ? GeminiModelAdapter.fingerprint(model.modelId()) : runtime.artifactFingerprint(),
                runtime.runtimeVersion(), model.baseUrl(), model.contextTokens(),
                runtime.promptVersion()), runtime.temperature(), runtime.seed(), harness.outputTokens(), harness.modelCallSeconds());
    }
}
