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
package org.glavo.avif.av1;

import org.glavo.avif.Av1DecodedPlane;
import org.glavo.avif.Av1DecodedPlanes;
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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies that the exported raw AV1 API does not expose implementation-only types.
@NotNullByDefault
final class Av1PublicApiBoundaryTest {
    /// The package that owns every public raw AV1 API type.
    private static final String PUBLIC_AV1_PACKAGE = "org.glavo.avif.av1";
    /// The implementation package prefix forbidden in exported signatures.
    private static final String INTERNAL_PACKAGE_PREFIX = "org.glavo.avif.internal.";

    /// Verifies public fields, constructors, and methods on the raw AV1 boundary types.
    @Test
    void exportedSignaturesDoNotReferenceInternalPackages() {
        List<Class<?>> av1ApiTypes = List.of(
                Av1Decoder.class,
                Av1DecodedOutput.class,
                Av1DecodedFrame.class,
                Av1ColorConfig.class,
                Av1DecoderConfig.class,
                Av1FrameSelection.class,
                Av1FrameType.class,
                Av1DecodeException.class,
                Av1DecodeErrorCode.class,
                Av1DecodeStage.class
        );
        for (Class<?> apiType : av1ApiTypes) {
            assertEquals(PUBLIC_AV1_PACKAGE, apiType.getPackageName());
            assertPublicSignatures(apiType);
        }
        for (Class<?> sharedApiType : List.of(
                Av1DecodedPlane.class,
                Av1DecodedPlanes.class
        )) {
            assertPublicSignatures(sharedApiType);
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
        assertExportedType(type, signature, new HashSet<>());
    }

    /// Verifies one type reference while breaking recursive generic-bound cycles.
    ///
    /// @param type the reflected type to inspect
    /// @param signature the owning signature used in an assertion message
    /// @param visited the type references already traversed for this signature
    private static void assertExportedType(Type type, String signature, Set<Type> visited) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> concreteType) {
            assertFalse(
                    concreteType.getName().startsWith(INTERNAL_PACKAGE_PREFIX),
                    () -> signature + " exposes " + concreteType.getName()
            );
            if (concreteType.isArray()) {
                assertExportedType(concreteType.getComponentType(), signature, visited);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            assertExportedType(parameterizedType.getRawType(), signature, visited);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertExportedType(argument, signature, visited);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            assertExportedType(arrayType.getGenericComponentType(), signature, visited);
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getLowerBounds()) {
                assertExportedType(bound, signature, visited);
            }
            for (Type bound : wildcardType.getUpperBounds()) {
                assertExportedType(bound, signature, visited);
            }
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                assertExportedType(bound, signature, visited);
            }
        }
    }
}
