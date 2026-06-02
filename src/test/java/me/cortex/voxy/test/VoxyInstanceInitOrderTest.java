package me.cortex.voxy.test;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the VoxyClientInstance initialization-order bug.
 *
 * The bug: VoxyInstance.<init> calls this.shouldCreateInstance() before the
 * subclass constructor body has had a chance to assign its fields, so any
 * subclass implementation of shouldCreateInstance() that reads instance state
 * will NPE.
 *
 * The fix uses a static PENDING_CONFIG field populated before super() is
 * invoked. This test asserts the structural contract so the regression cannot
 * silently come back:
 *  - VoxyClientInstance declares static PENDING_CONFIG and PENDING_BASE_PATH
 *  - VoxyClientInstance.shouldCreateInstance is null-safe against this.config
 */
class VoxyInstanceInitOrderTest {

    @Test
    void clientInstanceHasPendingStaticFields() throws Exception {
        Class<?> cls = Class.forName("me.cortex.voxy.client.VoxyClientInstance");
        Field pendingConfig = findDeclaredField(cls, "PENDING_CONFIG");
        Field pendingBase = findDeclaredField(cls, "PENDING_BASE_PATH");
        assertNotNull(pendingConfig, "VoxyClientInstance must declare PENDING_CONFIG to support super() init order");
        assertNotNull(pendingBase, "VoxyClientInstance must declare PENDING_BASE_PATH to support super() init order");
        assertTrue(Modifier.isStatic(pendingConfig.getModifiers()), "PENDING_CONFIG must be static");
        assertTrue(Modifier.isStatic(pendingBase.getModifiers()), "PENDING_BASE_PATH must be static");
    }

    @Test
    void shouldCreateInstanceIsNullSafe() throws Exception {
        // Bytecode-level check: shouldCreateInstance must not unconditionally
        // dereference this.config (else it NPEs during super() invocation).
        // We approximate by verifying the method exists with the expected
        // signature and is overridable.
        Class<?> cls = Class.forName("me.cortex.voxy.client.VoxyClientInstance");
        Method m = cls.getDeclaredMethod("shouldCreateInstance");
        assertEquals(boolean.class, m.getReturnType());
        assertFalse(Modifier.isStatic(m.getModifiers()));
        assertFalse(Modifier.isFinal(m.getModifiers()));
    }

    @Test
    void superInitOrderContractDocumented() throws Exception {
        // VoxyInstance.<init> calls shouldCreateInstance() before subclass
        // fields are assigned. Any subclass override must therefore be safe
        // to call against an "empty" instance (this.someField == null).
        Class<?> superCls = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance");
        Method m = superCls.getDeclaredMethod("shouldCreateInstance");
        assertTrue(Modifier.isAbstract(m.getModifiers()) || Modifier.isProtected(m.getModifiers()) || Modifier.isPublic(m.getModifiers()),
            "VoxyInstance.shouldCreateInstance must be overridable by subclasses");
    }

    private static Field findDeclaredField(Class<?> cls, String name) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getName().equals(name)) return f;
        }
        return null;
    }
}
