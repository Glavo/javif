// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/// Verifies that reconstruction scratch storage remains scoped to decoder-owned instances.
@NotNullByDefault
final class ReconstructionWorkspaceOwnershipTest {
    /// Verifies that reconstruction workspace owners do not reintroduce thread-bound fields.
    @Test
    void workspaceOwnersDeclareNoThreadLocalFields() {
        for (Class<?> owner : List.of(
                FrameReconstructor.class,
                IntraPredictor.class,
                InverseTransformer.class
        )) {
            assertFalse(
                    Arrays.stream(owner.getDeclaredFields())
                            .anyMatch(field -> ThreadLocal.class.isAssignableFrom(field.getType())),
                    owner.getName()
            );
        }
    }

    /// Verifies that each frame reconstructor owns a distinct graph of mutable helper state.
    @Test
    void frameReconstructorsOwnDistinctWorkspaceGraphs() {
        FrameReconstructor first = new FrameReconstructor();
        FrameReconstructor second = new FrameReconstructor();

        for (String fieldName : List.of(
                "interPredictionWorkspace",
                "intraPredictor",
                "inverseTransformer"
        )) {
            Field field = declaredField(FrameReconstructor.class, fieldName);
            assertFalse(Modifier.isStatic(field.getModifiers()), fieldName);
            assertNotSame(fieldValue(field, first), fieldValue(field, second), fieldName);
        }
    }

    /// Returns one declared field after enabling reflective test access.
    ///
    /// @param owner the class that declares the field
    /// @param fieldName the exact field name
    /// @return the accessible declared field
    private static Field declaredField(Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect field " + owner.getName() + "." + fieldName, exception);
        }
    }

    /// Returns the value of one accessible instance field.
    ///
    /// @param field the accessible field to read
    /// @param owner the field owner
    /// @return the non-null field value
    private static Object fieldValue(Field field, Object owner) {
        try {
            Object value = field.get(owner);
            if (value == null) {
                throw new AssertionError("Unexpected null field: " + field.getName());
            }
            return value;
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Failed to read field " + field.getName(), exception);
        }
    }
}
