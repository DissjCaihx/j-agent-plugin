package com.jagent.api;

import java.lang.instrument.ClassFileTransformer;

public interface InstrumentationManager {
    void registerTransformer(ClassFileTransformer transformer, boolean canRetransform);

    void unregisterTransformer(ClassFileTransformer transformer);
}
