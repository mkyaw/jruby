
package org.jruby.ext.ffi.jffi;

import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Closure;
import com.kenai.jffi.ClosureManager;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyProc;
import org.jruby.ext.ffi.ArrayMemoryIO;
import org.jruby.ext.ffi.CallbackInfo;
import org.jruby.ext.ffi.DirectMemoryIO;
import org.jruby.ext.ffi.MappedType;
import org.jruby.ext.ffi.MemoryIO;
import org.jruby.ext.ffi.NullMemoryIO;
import org.jruby.ext.ffi.Platform;
import org.jruby.ext.ffi.Pointer;
import org.jruby.ext.ffi.Struct;
import org.jruby.ext.ffi.StructByValue;
import org.jruby.ext.ffi.Type;
import org.jruby.ext.ffi.Util;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;


/**
 * Manages Callback instances for the low level FFI backend.
 */
public class CallbackManager extends org.jruby.ext.ffi.CallbackManager {
    private static final int LONG_SIZE = Platform.getPlatform().longSize();
    private static final String CALLBACK_ID = "ffi_callback";

    /** Holder for the single instance of CallbackManager */
    private static final class SingletonHolder {
        static final CallbackManager INSTANCE = new CallbackManager();
    }

    /** 
     * Gets the singleton instance of CallbackManager
     */
    public static final CallbackManager getInstance() {
        return SingletonHolder.INSTANCE;
    }
    
    /**
     * Creates a Callback class for a ruby runtime
     *
     * @param runtime The runtime to create the class for
     * @param module The module to place the class in
     *
     * @return The newly created ruby class
     */
    public static RubyClass createCallbackClass(Ruby runtime, RubyModule module) {

        RubyClass cbClass = module.defineClassUnder("Callback", module.getClass("Pointer"),
                ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR);

        cbClass.defineAnnotatedMethods(NativeCallbackPointer.class);
        cbClass.defineAnnotatedConstants(NativeCallbackPointer.class);

        return cbClass;
    }
    
    public final org.jruby.ext.ffi.Pointer getCallback(Ruby runtime, CallbackInfo cbInfo, Object proc) {
        return proc instanceof RubyObject
                ? getCallback(runtime, cbInfo, (RubyObject) proc)
                : newCallback(runtime, cbInfo, proc);
    }

    /**
     * Gets a Callback object conforming to the signature contained in the
     * <tt>CallbackInfo</tt> for the ruby <tt>Proc</tt> or <tt>Block</tt> instance.
     *
     * @param runtime The ruby runtime the callback is attached to
     * @param cbInfo The signature of the native callback
     * @param proc The ruby object to call when the callback is invoked.
     * @return A native value returned to the native caller.
     */
    public final org.jruby.ext.ffi.Pointer getCallback(Ruby runtime, CallbackInfo cbInfo, RubyObject proc) {
        if (proc instanceof Function) {
            return (Function) proc;
        }

        synchronized (proc) {
            Object existing = proc.getInternalVariable(CALLBACK_ID);
            if (existing instanceof NativeCallbackPointer && ((NativeCallbackPointer) existing).cbInfo == cbInfo) {
                return (NativeCallbackPointer) existing;
            } else if (existing instanceof Map) {
                Map m = (Map) existing;
                NativeCallbackPointer cb = (NativeCallbackPointer) m.get(proc);
                if (cb != null) {
                    return cb;
                }
            }

            NativeCallbackPointer cb = newCallback(runtime, cbInfo, proc);
            
            if (existing == null) {
                ((RubyObject) proc).setInternalVariable(CALLBACK_ID, cb);
            } else {
                Map<CallbackInfo, NativeCallbackPointer> m = existing instanceof Map
                        ? (Map<CallbackInfo, NativeCallbackPointer>) existing
                        : Collections.synchronizedMap(new WeakHashMap<CallbackInfo, NativeCallbackPointer>());
                m.put(cbInfo, cb);
                m.put(((NativeCallbackPointer) existing).cbInfo, (NativeCallbackPointer) existing);
                ((RubyObject) proc).setInternalVariable(CALLBACK_ID, m);
            }

            return cb;
        }
        
    }

    /**
     * Gets a Callback object conforming to the signature contained in the
     * <tt>CallbackInfo</tt> for the ruby <tt>Proc</tt> or <tt>Block</tt> instance.
     *
     * @param runtime The ruby runtime the callback is attached to
     * @param cbInfo The signature of the native callback
     * @param proc The ruby <tt>Block</tt> object to call when the callback is invoked.
     * @return A native value returned to the native caller.
     */
    final NativeCallbackPointer getCallback(Ruby runtime, CallbackInfo cbInfo, Block proc) {
        return newCallback(runtime, cbInfo, proc);
    }

    private final NativeCallbackPointer newCallback(Ruby runtime, CallbackInfo cbInfo, Object proc) {
        NativeFunctionInfo info = getClosureInfo(runtime, cbInfo);
        CallbackProxy cbProxy = new CallbackProxy(runtime, info, proc);
        Closure.Handle handle = ClosureManager.getInstance().newClosure(cbProxy, info.callContext);
        return new NativeCallbackPointer(runtime, handle, cbInfo, info);
    }

    private final NativeFunctionInfo getClosureInfo(Ruby runtime, CallbackInfo cbInfo) {
        Object info = cbInfo.getProviderCallbackInfo();
        if (info != null && info instanceof NativeFunctionInfo) {
            return (NativeFunctionInfo) info;
        }

        cbInfo.setProviderCallbackInfo(info = newClosureInfo(runtime, cbInfo));

        return (NativeFunctionInfo) info;
    }

    private final NativeFunctionInfo newClosureInfo(Ruby runtime, CallbackInfo cbInfo) {

        Type[] paramTypes = cbInfo.getParameterTypes();
        for (int i = 0; i < paramTypes.length; ++i) {
            if (!isParameterTypeValid(paramTypes[i]) || FFIUtil.getFFIType(paramTypes[i]) == null) {
                throw runtime.newTypeError("invalid callback parameter type: " + paramTypes[i]);
            }
        }

        if (!isReturnTypeValid(cbInfo.getReturnType()) || FFIUtil.getFFIType(cbInfo.getReturnType()) == null) {
            runtime.newTypeError("invalid callback return type: " + cbInfo.getReturnType());
        }

        return new NativeFunctionInfo(runtime, cbInfo.getReturnType(), cbInfo.getParameterTypes(),
                cbInfo.isStdcall() ? CallingConvention.STDCALL : CallingConvention.DEFAULT);
    }

    /**
     */
    final CallbackMemoryIO newClosure(Ruby runtime, Type returnType, Type[] parameterTypes, 
            Object proc, CallingConvention convention) {
        NativeFunctionInfo info = new NativeFunctionInfo(runtime, returnType, parameterTypes, convention);

        final CallbackProxy cbProxy = new CallbackProxy(runtime, info, proc);
        final Closure.Handle handle = ClosureManager.getInstance().newClosure(cbProxy, info.callContext);
        
        return new CallbackMemoryIO(runtime, handle, proc);
    }

    /**
     * Wraps a ruby proc in a JFFI Closure
     */
    private static final class CallbackProxy implements Closure {
        protected final Ruby runtime;
        protected final NativeFunctionInfo closureInfo;
        private final WeakReference<Object> proc;
        
        CallbackProxy(Ruby runtime, NativeFunctionInfo closureInfo, Object proc) {
            this.runtime = runtime;
            this.closureInfo = closureInfo;
            this.proc = new WeakReference<Object>(proc);
        }

        public void invoke(Closure.Buffer buffer) {
            Object recv = proc.get();
            if (recv == null) {
                buffer.setIntReturn(0);
                return;
            }
            invoke(buffer, recv);
        }

        protected final void invoke(Closure.Buffer buffer, Object recv) {
            ThreadContext context = runtime.getCurrentContext();

            IRubyObject[] params = new IRubyObject[closureInfo.parameterTypes.length];
            for (int i = 0; i < params.length; ++i) {
                params[i] = fromNative(runtime, closureInfo.parameterTypes[i], buffer, i);
            }

            IRubyObject retVal;
            if (recv instanceof RubyProc) {
                retVal = ((RubyProc) recv).call(context, params);
            } else if (recv instanceof Block) {
                retVal = ((Block) recv).call(context, params);
            } else {
                retVal = ((IRubyObject) recv).callMethod(context, "call", params);
            }

            setReturnValue(runtime, closureInfo.returnType, buffer, retVal);
        }
    }

    /**
     * Extracts the primitive value from a Ruby object.
     * This is similar to Util.longValue(), except it won't throw exceptions for
     * invalid values.
     *
     * @param value The Ruby object to convert
     * @return a java long value.
     */
    private static final long longValue(IRubyObject value) {
        if (value instanceof RubyNumeric) {
            return ((RubyNumeric) value).getLongValue();
        } else if (value.isNil()) {
            return 0L;
        }
        return 0;
    }

    /**
     * Extracts the primitive value from a Ruby object.
     * This is similar to Util.longValue(), except it won't throw exceptions for
     * invalid values.
     *
     * @param value The Ruby object to convert
     * @return a java long value.
     */
    private static final long addressValue(IRubyObject value) {
        if (value instanceof RubyNumeric) {
            return ((RubyNumeric) value).getLongValue();
        } else if (value instanceof Pointer) {
            return ((Pointer) value).getAddress();
        } else if (value.isNil()) {
            return 0L;
        }
        return 0;
    }

    /**
     * Converts a ruby return value into a native callback return value.
     *
     * @param runtime The ruby runtime the callback is attached to
     * @param type The ruby type of the return value
     * @param buffer The native parameter buffer
     * @param value The ruby value
     */
    private static final void setReturnValue(Ruby runtime, Type type,
            Closure.Buffer buffer, IRubyObject value) {
        if (type instanceof Type.Builtin) {
            switch (type.getNativeType()) {
                case VOID:
                    break;
                case CHAR:
                    buffer.setByteReturn((byte) longValue(value)); break;
                case UCHAR:
                    buffer.setByteReturn((byte) longValue(value)); break;
                case SHORT:
                    buffer.setShortReturn((short) longValue(value)); break;
                case USHORT:
                    buffer.setShortReturn((short) longValue(value)); break;
                case INT:
                    buffer.setIntReturn((int) longValue(value)); break;
                case UINT:
                    buffer.setIntReturn((int) longValue(value)); break;
                case LONG_LONG:
                    buffer.setLongReturn(Util.int64Value(value)); break;
                case ULONG_LONG:
                    buffer.setLongReturn(Util.uint64Value(value)); break;

                case LONG:
                    if (LONG_SIZE == 32) {
                        buffer.setIntReturn((int) longValue(value));
                    } else {
                        buffer.setLongReturn(Util.int64Value(value));
                    }
                    break;

                case ULONG:
                    if (LONG_SIZE == 32) {
                        buffer.setIntReturn((int) longValue(value));
                    } else {
                        buffer.setLongReturn(Util.uint64Value(value));
                    }
                    break;

                case FLOAT:
                    buffer.setFloatReturn((float) RubyNumeric.num2dbl(value)); break;
                case DOUBLE:
                    buffer.setDoubleReturn(RubyNumeric.num2dbl(value)); break;
                case POINTER:
                    buffer.setAddressReturn(addressValue(value)); break;

                case BOOL:
                    buffer.setIntReturn(value.isTrue() ? 1 : 0); break;
                default:
            }
        } else if (type instanceof CallbackInfo) {
            if (value instanceof RubyProc || value.respondsTo("call")) {
                Pointer cb = Factory.getInstance().getCallbackManager().getCallback(runtime, (CallbackInfo) type, value);
                buffer.setAddressReturn(addressValue(cb));
            } else {
                buffer.setAddressReturn(0L);
                throw runtime.newTypeError("invalid callback return value, expected Proc or callable object");
            }

        } else if (type instanceof StructByValue) {

            if (value instanceof Struct) {
                Struct s = (Struct) value;
                MemoryIO memory = s.getMemory().getMemoryIO();

                if (memory instanceof DirectMemoryIO) {
                    long address = ((DirectMemoryIO) memory).getAddress();
                    if (address != 0) {
                        buffer.setStructReturn(address);
                    } else {
                        // Zero it out
                        buffer.setStructReturn(new byte[type.getNativeSize()], 0);
                    }

                } else if (memory instanceof ArrayMemoryIO) {
                    ArrayMemoryIO arrayMemory = (ArrayMemoryIO) memory;
                    if (arrayMemory.arrayLength() < type.getNativeSize()) {
                        throw runtime.newRuntimeError("size of struct returned from callback too small");
                    }

                    buffer.setStructReturn(arrayMemory.array(), arrayMemory.arrayOffset());

                } else {
                    throw runtime.newRuntimeError("struct return value has illegal backing memory");
                }
            } else if (value.isNil()) {
                // Zero it out
                buffer.setStructReturn(new byte[type.getNativeSize()], 0);
            
            } else {
                throw runtime.newTypeError(value, runtime.getModule("FFI").getClass("Struct"));
            }

        } else if (type instanceof MappedType) {
            MappedType mappedType = (MappedType) type;
            setReturnValue(runtime, mappedType.getRealType(), buffer, mappedType.toNative(runtime.getCurrentContext(), value));

        } else {
            buffer.setLongReturn(0L);
            throw runtime.newRuntimeError("unsupported return type from struct: " + type);
        }
    }

    /**
     * Converts a native value into a ruby object.
     *
     * @param runtime The ruby runtime to create the ruby object in
     * @param type The type of the native parameter
     * @param buffer The JFFI Closure parameter buffer.
     * @param index The index of the parameter in the buffer.
     * @return A new Ruby object.
     */
    private static final IRubyObject fromNative(Ruby runtime, Type type,
            Closure.Buffer buffer, int index) {
        if (type instanceof Type.Builtin) {
            switch (type.getNativeType()) {
                case VOID:
                    return runtime.getNil();
                case CHAR:
                    return Util.newSigned8(runtime, buffer.getByte(index));
                case UCHAR:
                    return Util.newUnsigned8(runtime, buffer.getByte(index));
                case SHORT:
                    return Util.newSigned16(runtime, buffer.getShort(index));
                case USHORT:
                    return Util.newUnsigned16(runtime, buffer.getShort(index));
                case INT:
                    return Util.newSigned32(runtime, buffer.getInt(index));
                case UINT:
                    return Util.newUnsigned32(runtime, buffer.getInt(index));
                case LONG_LONG:
                    return Util.newSigned64(runtime, buffer.getLong(index));
                case ULONG_LONG:
                    return Util.newUnsigned64(runtime, buffer.getLong(index));

                case LONG:
                    return LONG_SIZE == 32
                            ? Util.newSigned32(runtime, buffer.getInt(index))
                            : Util.newSigned64(runtime, buffer.getLong(index));
                case ULONG:
                    return LONG_SIZE == 32
                            ? Util.newUnsigned32(runtime, buffer.getInt(index))
                            : Util.newUnsigned64(runtime, buffer.getLong(index));

                case FLOAT:
                    return runtime.newFloat(buffer.getFloat(index));
                case DOUBLE:
                    return runtime.newFloat(buffer.getDouble(index));

                case POINTER:
                    return new Pointer(runtime, NativeMemoryIO.wrap(runtime, buffer.getAddress(index)));

                case STRING:
                    return getStringParameter(runtime, buffer, index);

                case BOOL:
                    return runtime.newBoolean(buffer.getByte(index) != 0);

                default:
                    throw runtime.newTypeError("invalid callback parameter type " + type);
            }
        
        } else if (type instanceof CallbackInfo) {
            final CallbackInfo cbInfo = (CallbackInfo) type;
            final long address = buffer.getAddress(index);
            
            return address != 0
                ? new Function(runtime, cbInfo.getMetaClass(),
                    new CodeMemoryIO(runtime, address),
                    cbInfo.getReturnType(), cbInfo.getParameterTypes(),
                    cbInfo.isStdcall() ? CallingConvention.STDCALL : CallingConvention.DEFAULT, runtime.getNil())

                : runtime.getNil();

        } else if (type instanceof StructByValue) {
            StructByValue sbv = (StructByValue) type;
            final long address = buffer.getStruct(index);
            DirectMemoryIO memory = address != 0
                    ? new BoundedNativeMemoryIO(runtime, address, type.getNativeSize())
                    : new NullMemoryIO(runtime);

            return sbv.getStructClass().newInstance(runtime.getCurrentContext(),
                        new IRubyObject[] { new Pointer(runtime, memory) },
                        Block.NULL_BLOCK);

        } else if (type instanceof MappedType) {
            MappedType mappedType = (MappedType) type;
            return mappedType.fromNative(runtime.getCurrentContext(), fromNative(runtime, mappedType.getRealType(), buffer, index));

        } else {
            throw runtime.newTypeError("unsupported callback parameter type: " + type);
        }

    }

    /**
     * Converts a native string value into a ruby string object.
     *
     * @param runtime The ruby runtime to create the ruby string in
     * @param buffer The JFFI Closure parameter buffer.
     * @param index The index of the parameter in the buffer.
     * @return A new Ruby string object or nil if string is NULL.
     */
    private static final IRubyObject getStringParameter(Ruby runtime, Closure.Buffer buffer, int index) {
        return FFIUtil.getString(runtime, buffer.getAddress(index));
    }

    /**
     * Checks if a type is a valid callback return type
     *
     * @param type The type to examine
     * @return <tt>true</tt> if <tt>type</tt> is a valid return type for a callback.
     */
    private static final boolean isReturnTypeValid(Type type) {
        if (type instanceof Type.Builtin) {
            switch (type.getNativeType()) {
                case CHAR:
                case UCHAR:
                case SHORT:
                case USHORT:
                case INT:
                case UINT:
                case LONG:
                case ULONG:
                case LONG_LONG:
                case ULONG_LONG:
                case FLOAT:
                case DOUBLE:
                case POINTER:
                case VOID:
                case BOOL:
                    return true;
            }

        } else if (type instanceof CallbackInfo) {
            return true;

        } else if (type instanceof StructByValue) {
            return true;
        }
        return false;
    }
    
    /**
     * Checks if a type is a valid parameter type for a callback
     *
     * @param type The type to examine
     * @return <tt>true</tt> if <tt>type</tt> is a valid parameter type for a callback.
     */
    private static final boolean isParameterTypeValid(Type type) {
        if (type instanceof Type.Builtin) {
            switch (type.getNativeType()) {
                case CHAR:
                case UCHAR:
                case SHORT:
                case USHORT:
                case INT:
                case UINT:
                case LONG:
                case ULONG:
                case LONG_LONG:
                case ULONG_LONG:
                case FLOAT:
                case DOUBLE:
                case POINTER:
                case STRING:
                case BOOL:
                    return true;
            }
        } else if (type instanceof CallbackInfo) {
            return true;
        
        } else if (type instanceof StructByValue) {
            return true;
        
        } else if (type instanceof MappedType) {
            return isParameterTypeValid(((MappedType) type).getRealType());
        }
        
        return false;
    }
}
