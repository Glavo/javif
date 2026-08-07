/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.avif.decode;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies that the exported raw AV1 API does not expose implementation-only types.
@NotNullByDefault
final class Av1PublicApiBoundaryTest {
    /// The implementation package prefix forbidden in exported signatures.
    private static final String INTERNAL_PACKAGE_PREFIX = "org.glavo.avif.internal.";

    /// Verifies public fields, constructors, and methods on the raw AV1 boundary types.
    @Test
    void exportedSignaturesDoNotReferenceInternalPackages() {
        for (Class<?> apiType : List.of(
                Av1ImageReader.class,
                Av1ColorConfig.class,
                DecodedPlane.class,
                DecodedPlanes.class
        )) {
            assertPublicSignatures(apiType);
        }
    }

    /// Verifies every public signature declared or inherited by one API type.
    ///
    /// @param apiType the API type to inspect
    private static void assertPublicSignatures(Class<?> apiType) {
        for (Field field : apiType.getFields()) {
            assertExportedType(field.getGenericType(), field.toGenericString());
        }
        for (Constructor<?> constructor : apiType.getConstructors()) {
            for (Type parameterType : constructor.getGenericParameterTypes()) {
                assertExportedType(parameterType, constructor.toGenericString());
            }
            for (Type exceptionType : constructor.getGenericExceptionTypes()) {
                assertExportedType(exceptionType, constructor.toGenericString());
            }
        }
        for (Method method : apiType.getMethods()) {
            assertExportedType(method.getGenericReturnType(), method.toGenericString());
            for (Type parameterType : method.getGenericParameterTypes()) {
                assertExportedType(parameterType, method.toGenericString());
            }
            for (Type exceptionType : method.getGenericExceptionTypes()) {
                assertExportedType(exceptionType, method.toGenericString());
            }
        }
    }

    /// Verifies one possibly nested generic type reference.
    ///
    /// @param type the reflected type to inspect
    /// @param signature the owning signature used in an assertion message
    private static void assertExportedType(Type type, String signature) {
        if (type instanceof Class<?> concreteType) {
            assertFalse(
                    concreteType.getName().startsWith(INTERNAL_PACKAGE_PREFIX),
                    () -> signature + " exposes " + concreteType.getName()
            );
            if (concreteType.isArray()) {
                assertExportedType(concreteType.getComponentType(), signature);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            assertExportedType(parameterizedType.getRawType(), signature);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertExportedType(argument, signature);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            assertExportedType(arrayType.getGenericComponentType(), signature);
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getLowerBounds()) {
                assertExportedType(bound, signature);
            }
            for (Type bound : wildcardType.getUpperBounds()) {
                assertExportedType(bound, signature);
            }
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                assertExportedType(bound, signature);
            }
        }
    }
}
