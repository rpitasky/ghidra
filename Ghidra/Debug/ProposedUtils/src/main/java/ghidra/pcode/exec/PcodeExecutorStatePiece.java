/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.pcode.exec;

import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.MemBuffer;
import ghidra.program.model.pcode.Varnode;

/**
 * An interface that provides storage for values of type {@code T}, addressed by offsets of type
 * {@code A}
 * 
 * <p>
 * The typical pattern for implementing a state is to compose it from one or more state pieces. Each
 * piece must use the same address type and arithmetic. If more than one piece is needed, they are
 * composed using {@link PairedPcodeExecutorStatePiece}. Once all the pieces are composed, the root
 * piece can be wrapped to make a state using {@link DefaultPcodeExecutorState} or
 * {@link PairedPcodeExecutorState}. The latter corrects the address type to be a pair so it matches
 * the type of values.
 *
 * @param <A> the type of address offsets
 * @param <T> the type of values
 */
public interface PcodeExecutorStatePiece<A, T> {

	/**
	 * Construct a range, if only to verify the range is valid
	 * 
	 * @param space the address space
	 * @param offset the starting offset
	 * @param size the length (in bytes) of the range
	 */
	default void checkRange(AddressSpace space, long offset, int size) {
		// TODO: Perhaps get/setVar should just take an AddressRange?
		try {
			new AddressRangeImpl(space.getAddress(offset), size);
		}
		catch (AddressOverflowException | AddressOutOfBoundsException e) {
			throw new IllegalArgumentException("Given offset and length exceeds address space");
		}
	}

	/**
	 * Get the arithmetic used to manipulate addresses of the type used by this state
	 * 
	 * @return the address (or offset) arithmetic
	 */
	PcodeArithmetic<A> getAddressArithmetic();

	/**
	 * Get the arithmetic used to manipulate values of the type stored by this state
	 * 
	 * @return the arithmetic
	 */
	PcodeArithmetic<T> getArithmetic();

	/**
	 * Set the value of a register variable
	 * 
	 * @param reg the register
	 * @param val the value
	 */
	default void setVar(Register reg, T val) {
		Address address = reg.getAddress();
		setVar(address.getAddressSpace(), address.getOffset(), reg.getMinimumByteSize(), true, val);
	}

	/**
	 * Set the value of a variable
	 * 
	 * @param var the variable
	 * @param val the value
	 */
	default void setVar(Varnode var, T val) {
		Address address = var.getAddress();
		setVar(address.getAddressSpace(), address.getOffset(), var.getSize(), true, val);
	}

	/**
	 * Set the value of a variable
	 * 
	 * @param space the address space
	 * @param offset the offset within the space
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @param val the value
	 */
	void setVar(AddressSpace space, A offset, int size, boolean quantize, T val);

	/**
	 * Set the value of a variable
	 * 
	 * @param space the address space
	 * @param offset the offset within the space
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @param val the value
	 */
	default void setVar(AddressSpace space, long offset, int size, boolean quantize, T val) {
		checkRange(space, offset, size);
		A aOffset = getAddressArithmetic().fromConst(offset, space.getPointerSize());
		setVar(space, aOffset, size, quantize, val);
	}

	/**
	 * Set the value of a variable
	 * 
	 * @param address the address in memory
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @param val the value
	 */
	default void setVar(Address address, int size, boolean quantize, T val) {
		setVar(address.getAddressSpace(), address.getOffset(), size, quantize, val);
	}

	/**
	 * Get the value of a register variable
	 * 
	 * @param reg the register
	 * @return the value
	 */
	default T getVar(Register reg) {
		Address address = reg.getAddress();
		return getVar(address.getAddressSpace(), address.getOffset(), reg.getMinimumByteSize(),
			true);
	}

	/**
	 * Get the value of a variable
	 * 
	 * @param var the variable
	 * @return the value
	 */
	default T getVar(Varnode var) {
		Address address = var.getAddress();
		return getVar(address.getAddressSpace(), address.getOffset(), var.getSize(), true);
	}

	/**
	 * Get the value of a variable
	 * 
	 * @param space the address space
	 * @param offset the offset within the space
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @return the value
	 */
	T getVar(AddressSpace space, A offset, int size, boolean quantize);

	/**
	 * Get the value of a variable
	 * 
	 * <p>
	 * This method is typically used for reading memory variables.
	 * 
	 * @param space the address space
	 * @param offset the offset within the space
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @return the value
	 */
	default T getVar(AddressSpace space, long offset, int size, boolean quantize) {
		checkRange(space, offset, size);
		A aOffset = getAddressArithmetic().fromConst(offset, space.getPointerSize());
		return getVar(space, aOffset, size, quantize);
	}

	/**
	 * Get the value of a variable
	 * 
	 * <p>
	 * This method is typically used for reading memory variables.
	 * 
	 * @param address the address of the variable
	 * @param size the size of the variable
	 * @param quantize true to quantize to the language's "addressable unit"
	 * @return the value
	 */
	default T getVar(Address address, int size, boolean quantize) {
		return getVar(address.getAddressSpace(), address.getOffset(), size, quantize);
	}

	/**
	 * Bind a buffer of concrete bytes at the given start address
	 * 
	 * @param address the start address
	 * @param purpose the reason why the emulator needs a concrete value
	 * @return a buffer
	 */
	MemBuffer getConcreteBuffer(Address address, Purpose purpose);

	/**
	 * Quantize the given offset to the language's "addressable unit"
	 * 
	 * @param space the space where the offset applies
	 * @param offset the offset
	 * @return the quantized offset
	 */
	default long quantizeOffset(AddressSpace space, long offset) {
		return space.truncateAddressableWordOffset(offset) * space.getAddressableUnitSize();
	}
}
