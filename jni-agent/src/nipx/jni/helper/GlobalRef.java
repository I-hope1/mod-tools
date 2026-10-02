package nipx.jni.helper;

import nipx.jni.JNIEnv;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

public class GlobalRef implements AutoCloseable {

    private volatile MemorySegment globalRef;
    public final   boolean         isRef;
    public volatile JValue         jValue;
    private final  AtomicBoolean   closed = new AtomicBoolean(false);

    public GlobalRef(JNIEnv env, MemorySegment jobject) {
        if (jobject != null && jobject.address() != 0) {
            JNIEnv activeEnv = env != null ? env : JNIEnv.getInstance();
            globalRef = activeEnv.NewGlobalRef(jobject);
            isRef = true;
            jValue = new JValue(globalRef.address());
        } else {
            globalRef = MemorySegment.NULL;
            isRef = true;
            jValue = new JValue(0);
        }
    }

    public GlobalRef(MemorySegment jobject) {
        this(null, jobject);
    }

    public GlobalRef(JNIEnv env, JValue jobject) {
        isRef = false;
        jValue = jobject;
        globalRef = null;
    }

    public GlobalRef(JValue jobject) {
        this((JNIEnv) null, jobject);
    }

    public MemorySegment ref() {
        return globalRef;
    }

    public JValue jValue() {
        return jValue;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            MemorySegment ref = this.globalRef;
            this.globalRef = null;
            this.jValue = null;
            if (isRef && ref != null && ref.address() != 0) {
                JNIEnv.getInstance().DeleteGlobalRef(ref);
            }
        }
    }
}
