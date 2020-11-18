/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.builtins.modules;

import static com.oracle.graal.python.runtime.exception.PythonErrorType.MemoryError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.ValueError;

import java.util.List;

import com.oracle.graal.python.annotations.ArgumentClinic;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.array.ArrayBuiltins;
import com.oracle.graal.python.builtins.objects.array.ArrayNodes;
import com.oracle.graal.python.builtins.objects.array.PArray;
import com.oracle.graal.python.builtins.objects.bytes.PBytes;
import com.oracle.graal.python.builtins.objects.bytes.PBytesLike;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.builtins.objects.range.PIntRange;
import com.oracle.graal.python.builtins.objects.str.StringNodes;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.control.GetNextNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.builtins.PythonClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonVarargsBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.clinic.ArgumentClinicProvider;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.util.SplitArgsNode;
import com.oracle.graal.python.runtime.PythonCore;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.util.BufferFormat;
import com.oracle.graal.python.util.OverflowException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ValueProfile;

@CoreFunctions(defineModule = "array")
public final class ArrayModuleBuiltins extends PythonBuiltins {

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return ArrayModuleBuiltinsFactory.getFactories();
    }

    @Override
    public void postInitialize(PythonCore core) {
        super.postInitialize(core);
        PythonModule arrayModule = core.lookupBuiltinModule("array");
        arrayModule.setAttribute("ArrayType", core.lookupType(PythonBuiltinClassType.PArray));
        arrayModule.setAttribute("typecodes", "bBuhHiIlLqQfd");
    }

    // array.array(typecode[, initializer])
    @Builtin(name = "array", minNumOfPositionalArgs = 1, constructsClass = PythonBuiltinClassType.PArray, takesVarArgs = true, takesVarKeywordArgs = true, declaresExplicitSelf = true)
    @GenerateNodeFactory
    abstract static class ArrayNode extends PythonVarargsBuiltinNode {
        @Child private SplitArgsNode splitArgsNode;

        @Specialization(guards = "args.length == 1")
        Object array2(VirtualFrame frame, Object cls, Object[] args, PKeyword[] kwargs,
                        @Cached IsBuiltinClassProfile isNotSubtypeProfile,
                        @Cached StringNodes.CastToJavaStringCheckedNode cast,
                        @Cached ArrayNodeInternal arrayNodeInternal) {
            checkKwargs(cls, kwargs, isNotSubtypeProfile);
            return arrayNodeInternal.execute(frame, cls, cast.cast(args[0], "array() argument 1 must be a unicode character, not %p", args[0]), PNone.NO_VALUE);
        }

        @Specialization(guards = "args.length == 2")
        Object array3(VirtualFrame frame, Object cls, Object[] args, PKeyword[] kwargs,
                        @Cached IsBuiltinClassProfile isNotSubtypeProfile,
                        @Cached StringNodes.CastToJavaStringCheckedNode cast,
                        @Cached ArrayNodeInternal arrayNodeInternal) {
            checkKwargs(cls, kwargs, isNotSubtypeProfile);
            return arrayNodeInternal.execute(frame, cls, cast.cast(args[0], "array() argument 1 must be a unicode character, not %p", args[0]), args[1]);
        }

        @Fallback
        @SuppressWarnings("unused")
        Object error(Object cls, Object[] args, PKeyword[] kwargs) {
            if (args.length < 2) {
                throw raise(TypeError, "%s() takes at least %d arguments (%d given)", "array", 2, args.length);
            } else {
                throw raise(TypeError, "%s() takes at most %d arguments (%d given)", "array", 3, args.length);
            }
        }

        private void checkKwargs(Object cls, PKeyword[] kwargs, IsBuiltinClassProfile isNotSubtypeProfile) {
            if (isNotSubtypeProfile.profileClass(cls, PythonBuiltinClassType.PArray)) {
                if (kwargs.length != 0) {
                    throw raise(TypeError, "array.array() takes no keyword arguments");
                }
            }
        }

        @Override
        public final Object varArgExecute(VirtualFrame frame, @SuppressWarnings("unused") Object self, Object[] arguments, PKeyword[] keywords) throws VarargsBuiltinDirectInvocationNotSupported {
            if (splitArgsNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                splitArgsNode = insert(SplitArgsNode.create());
            }
            return execute(frame, arguments[0], splitArgsNode.execute(arguments), keywords);
        }

        @ImportStatic(PGuards.class)
        abstract static class ArrayNodeInternal extends Node {
            @Child private PRaiseNode raiseNode;
            @Child private PythonObjectFactory factory;
            @CompilationFinal private ValueProfile formatProfile = ValueProfile.createIdentityProfile();

            public abstract PArray execute(VirtualFrame frame, Object cls, String typeCode, Object initializer);

            @Specialization(guards = "isNoValue(initializer)")
            PArray array(Object cls, String typeCode, @SuppressWarnings("unused") PNone initializer) {
                BufferFormat format = getFormatChecked(typeCode);
                return getFactory().createArray(cls, typeCode, format);
            }

            @Specialization
            PArray arrayWithRangeInitializer(Object cls, String typeCode, PIntRange range,
                            @Cached ArrayNodes.PutValueNode putValueNode) {
                BufferFormat format = getFormatChecked(typeCode);
                PArray array;
                try {
                    array = getFactory().createArray(cls, typeCode, format, range.getIntLength());
                } catch (OverflowException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw raise(MemoryError);
                }

                int start = range.getIntStart();
                int stop = range.getIntStop();
                int step = range.getIntStep();

                for (int index = 0, value = start; value < stop; index++, value += step) {
                    putValueNode.execute(null, array, index, value);
                }

                return array;
            }

            @Specialization
            PArray arrayWithBytesInitializer(VirtualFrame frame, Object cls, String typeCode, PBytesLike bytes,
                            @Cached ArrayBuiltins.FromBytesNode fromBytesNode) {
                PArray array = getFactory().createArray(cls, typeCode, getFormatChecked(typeCode));
                fromBytesNode.execute(frame, array, bytes);
                return array;
            }

            // TODO impl for PSequence and PArray or use lenght_hint

            @Specialization(guards = "!isBytes(initializer)", limit = "3")
            PArray arrayIteratorInitializer(VirtualFrame frame, Object cls, String typeCode, Object initializer,
                            @CachedLibrary("initializer") PythonObjectLibrary lib,
                            @Cached ArrayNodes.PutValueNode putValueNode,
                            @Cached GetNextNode nextNode,
                            @Cached IsBuiltinClassProfile errorProfile) {
                Object iter = lib.getIteratorWithFrame(initializer, frame);

                BufferFormat format = getFormatChecked(typeCode);
                PArray array = getFactory().createArray(cls, typeCode, format);

                int length = 0;
                while (true) {
                    Object nextValue;
                    try {
                        nextValue = nextNode.execute(frame, iter);
                    } catch (PException e) {
                        e.expectStopIteration(errorProfile);
                        break;
                    }
                    try {
                        length = PythonUtils.addExact(length, 1);
                        array.ensureCapacity(length);
                    } catch (OverflowException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw raise(MemoryError);
                    }
                    putValueNode.execute(frame, array, length - 1, nextValue);
                }

                array.setLenght(length);
                return array;
            }

            private BufferFormat getFormatChecked(String typeCode) {
                if (typeCode.length() != 1) {
                    throw raise(TypeError, "array() argument 1 must be a unicode character, not str");
                }
                BufferFormat format = BufferFormat.forArray(typeCode);
                if (format == null) {
                    throw raise(ValueError, "bad typecode (must be b, B, u, h, H, i, I, l, L, q, Q, f or d)");
                }
                return formatProfile.profile(format);
            }

            private PException raise(PythonBuiltinClassType type, String message, Object... args) {
                if (raiseNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    raiseNode = insert(PRaiseNode.create());
                }
                throw raiseNode.raise(type, message, args);
            }

            private PException raise(PythonBuiltinClassType type) {
                if (raiseNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    raiseNode = insert(PRaiseNode.create());
                }
                throw raiseNode.raise(type);
            }

            private PythonObjectFactory getFactory() {
                if (factory == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    factory = insert(PythonObjectFactory.create());
                }
                return factory;
            }
        }
    }

    @Builtin(name = "_array_reconstructor", minNumOfPositionalArgs = 4, numOfPositionalOnlyArgs = 4, parameterNames = {"arrayType", "typeCode", "mformatCode", "items"})
    @ArgumentClinic(name = "typeCode", conversion = ArgumentClinic.ClinicConversion.String)
    @ArgumentClinic(name = "mformatCode", conversion = ArgumentClinic.ClinicConversion.Index, defaultValue = "0")
    @GenerateNodeFactory
    abstract static class ArrayReconstructorNode extends PythonClinicBuiltinNode {
        @Specialization
        Object reconstruct(VirtualFrame frame, Object arrayType, String typeCode, int mformatCode, PBytes bytes,
                        @Cached ArrayBuiltins.FromBytesNode fromBytesNode,
                        @Cached IsSubtypeNode isSubtypeNode) {
            BufferFormat format = BufferFormat.forArray(typeCode);
            if (format == null) {
                throw raise(ValueError, "bad typecode (must be b, B, u, h, H, i, I, l, L, q, Q, f or d)");
            }
            if (!isSubtypeNode.execute(frame, arrayType, PythonBuiltinClassType.PArray)) {
                throw raise(TypeError, "%n is not a subtype of array", arrayType);
            }
            PArray.MachineFormat expectedFormat = PArray.MachineFormat.forFormat(format);
            if (expectedFormat != null && expectedFormat.code == mformatCode) {
                PArray array = factory().createArray(arrayType, typeCode, format);
                fromBytesNode.execute(frame, array, bytes);
                return array;
            } else {
                // TODO implement decoding for arrays pickled on a machine of different architecture
                throw raise(PythonBuiltinClassType.NotImplementedError, "Cannot decode array format");
            }
        }

        @Specialization(guards = "!isPBytes(value)")
        @SuppressWarnings("unused")
        Object error(Object arrayType, String typeCode, int mformatCode, Object value) {
            throw raise(TypeError, "fourth argument should be bytes, not %p", value);
        }

        protected static boolean isPBytes(Object obj) {
            return obj instanceof PBytes;
        }

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return ArrayModuleBuiltinsClinicProviders.ArrayReconstructorNodeClinicProviderGen.INSTANCE;
        }
    }
}
