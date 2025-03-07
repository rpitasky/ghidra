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
package ghidra.app.plugin.core.debug.service.emulation;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import generic.Unique;
import generic.test.category.NightlyCategory;
import ghidra.app.plugin.assembler.Assembler;
import ghidra.app.plugin.assembler.Assemblers;
import ghidra.app.plugin.core.codebrowser.CodeBrowserPlugin;
import ghidra.app.plugin.core.debug.gui.AbstractGhidraHeadedDebuggerGUITest;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.util.ProgramLocation;
import ghidra.trace.model.Trace;
import ghidra.trace.model.memory.TraceMemorySpace;
import ghidra.trace.model.thread.TraceThread;
import ghidra.trace.model.time.schedule.TraceSchedule;
import ghidra.util.database.UndoableTransaction;
import ghidra.util.task.TaskMonitor;

@Category(NightlyCategory.class) // this may actually be an @PortSensitive test
public class DebuggerEmulationServiceTest extends AbstractGhidraHeadedDebuggerGUITest {
	protected DebuggerEmulationServicePlugin emulationPlugin;
	protected CodeBrowserPlugin codeBrowser;

	@Before
	public void setUpEmulationServiceTest() throws Exception {
		emulationPlugin = addPlugin(tool, DebuggerEmulationServicePlugin.class);
		// TODO: Action enablement doesn't work without CodeBrowser???
		// Probably missing some contextChanged, but I have no provider!
		// I need it for GoTo anyway
		codeBrowser = addPlugin(tool, CodeBrowserPlugin.class);
	}

	@Test
	public void testPureEmulation() throws Exception {
		createProgram();
		intoProject(program);
		Assembler asm = Assemblers.getAssembler(program);
		Memory memory = program.getMemory();
		Address addrText = addr(program, 0x00400000);
		Register regPC = program.getRegister("pc");
		Register regR0 = program.getRegister("r0");
		Register regR1 = program.getRegister("r1");
		try (UndoableTransaction tid = UndoableTransaction.start(program, "Initialize")) {
			MemoryBlock blockText = memory.createInitializedBlock(".text", addrText, 0x1000,
				(byte) 0, TaskMonitor.DUMMY, false);
			blockText.setExecute(true);
			memory.createInitializedBlock(".data", addr(program, 0x00600000), 0x1000, (byte) 0,
				TaskMonitor.DUMMY, false);
			asm.assemble(addrText, "mov r0, r1");
			program.getProgramContext()
					.setValue(regR1, addrText, addrText, new BigInteger("1234", 16));
		}

		programManager.openProgram(program);
		waitForSwing();
		codeBrowser.goTo(new ProgramLocation(program, addrText));
		waitForSwing();

		assertTrue(emulationPlugin.actionEmulateProgram.isEnabled());
		performAction(emulationPlugin.actionEmulateProgram);

		Trace trace = traceManager.getCurrentTrace();
		assertNotNull(trace);

		TraceThread thread = Unique.assertOne(trace.getThreadManager().getAllThreads());
		TraceMemorySpace regs = trace.getMemoryManager().getMemoryRegisterSpace(thread, false);
		assertEquals(new BigInteger("00400000", 16),
			regs.getViewValue(0, regPC).getUnsignedValue());
		assertEquals(new BigInteger("0000", 16), regs.getViewValue(0, regR0).getUnsignedValue());
		assertEquals(new BigInteger("1234", 16), regs.getViewValue(0, regR1).getUnsignedValue());

		long scratch =
			emulationPlugin.emulate(trace, TraceSchedule.parse("0:t0-1"), TaskMonitor.DUMMY);

		assertEquals(new BigInteger("00400002", 16),
			regs.getViewValue(scratch, regPC).getUnsignedValue());
		assertEquals(new BigInteger("1234", 16),
			regs.getViewValue(scratch, regR0).getUnsignedValue());
		assertEquals(new BigInteger("1234", 16),
			regs.getViewValue(scratch, regR1).getUnsignedValue());
	}

	@Test
	public void testPureEmulationHarvard() throws Exception {
		Language toyHv = getLanguageService().getLanguage(new LanguageID("Toy:BE:64:harvard"));
		createProgram(toyHv);
		intoProject(program);
		Assembler asm = Assemblers.getAssembler(program);
		Memory memory = program.getMemory();
		AddressSpace codeSpace = program.getLanguage().getDefaultSpace();
		AddressSpace dataSpace = program.getLanguage().getDefaultDataSpace();
		Address addrText = codeSpace.getAddress(0x00000400);
		Address addrData = dataSpace.getAddress(0x00000400);
		assertNotEquals(addrText, addrData);
		Register regPC = program.getRegister("pc");
		Register regR0 = program.getRegister("r0");
		Register regR1 = program.getRegister("r1");
		try (UndoableTransaction tid = UndoableTransaction.start(program, "Initialize")) {
			MemoryBlock blockText = memory.createInitializedBlock(".text", addrText, 0x1000,
				(byte) 0, TaskMonitor.DUMMY, false);
			blockText.setExecute(true);
			MemoryBlock blockData = memory.createInitializedBlock(".data", addrData, 0x1000,
				(byte) 0, TaskMonitor.DUMMY, false);
			asm.assemble(addrText, "load r0, [r1]");
			program.getProgramContext()
					.setValue(regR1, addrText, addrText, new BigInteger("00000400", 16));
			blockData.putBytes(addrData, new byte[] { 0, 0, 0, 0, 0, 0, 0x12, 0x34 });
		}

		programManager.openProgram(program);
		waitForSwing();
		codeBrowser.goTo(new ProgramLocation(program, addrText));
		waitForSwing();

		assertTrue(emulationPlugin.actionEmulateProgram.isEnabled());
		performAction(emulationPlugin.actionEmulateProgram);

		Trace trace = traceManager.getCurrentTrace();
		assertNotNull(trace);

		TraceThread thread = Unique.assertOne(trace.getThreadManager().getAllThreads());
		TraceMemorySpace regs = trace.getMemoryManager().getMemoryRegisterSpace(thread, false);
		assertEquals(new BigInteger("00000400", 16),
			regs.getViewValue(0, regPC).getUnsignedValue());
		assertEquals(new BigInteger("0000", 16), regs.getViewValue(0, regR0).getUnsignedValue());
		assertEquals(new BigInteger("00000400", 16),
			regs.getViewValue(0, regR1).getUnsignedValue());

		long scratch =
			emulationPlugin.emulate(trace, TraceSchedule.parse("0:t0-1"), TaskMonitor.DUMMY);

		assertEquals(new BigInteger("00000402", 16),
			regs.getViewValue(scratch, regPC).getUnsignedValue());
		assertEquals(new BigInteger("1234", 16),
			regs.getViewValue(scratch, regR0).getUnsignedValue());
		assertEquals(new BigInteger("00000400", 16),
			regs.getViewValue(scratch, regR1).getUnsignedValue());
	}

	@Test
	public void testPureEmulationMemoryMappedPC_NonByteAddressable() throws Exception {
		Language pic33F =
			getLanguageService().getLanguage(new LanguageID("dsPIC33F:LE:24:default"));
		createProgram(pic33F);
		intoProject(program);
		Assembler asm = Assemblers.getAssembler(program);
		Memory memory = program.getMemory();
		AddressSpace codeSpace = program.getLanguage().getDefaultSpace();
		AddressSpace dataSpace = program.getLanguage().getDefaultDataSpace();
		Address addrText = codeSpace.getAddress(0x00000100, true);
		Address addrData = dataSpace.getAddress(0x00000800, true);
		assertNotEquals(addrText, addrData);
		Register regPC = program.getRegister("PC");
		Register regW0 = program.getRegister("W0");
		Register regW1 = program.getRegister("W1");
		try (UndoableTransaction tid = UndoableTransaction.start(program, "Initialize")) {
			MemoryBlock blockText = memory.createInitializedBlock(".text", addrText, 0x1000,
				(byte) 0, TaskMonitor.DUMMY, false);
			blockText.setExecute(true);
			MemoryBlock blockData = memory.createInitializedBlock(".data", addrData, 0x1000,
				(byte) 0, TaskMonitor.DUMMY, false);
			asm.assemble(addrText, "mov.w [W1], W0");
			program.getProgramContext()
					.setValue(regW1, addrText, addrText, new BigInteger("0800", 16));
			blockData.putBytes(addrData, new byte[] { 0x34, 0x12 }); // LE
		}

		programManager.openProgram(program);
		waitForSwing();
		codeBrowser.goTo(new ProgramLocation(program, addrText));
		waitForSwing();

		assertTrue(emulationPlugin.actionEmulateProgram.isEnabled());
		performAction(emulationPlugin.actionEmulateProgram);

		Trace trace = traceManager.getCurrentTrace();
		assertNotNull(trace);

		TraceThread thread = Unique.assertOne(trace.getThreadManager().getAllThreads());
		TraceMemorySpace regs = trace.getMemoryManager().getMemoryRegisterSpace(thread, false);
		assertEquals(new BigInteger("000100", 16),
			regs.getViewValue(0, regPC).getUnsignedValue());
		assertEquals(new BigInteger("0000", 16), regs.getViewValue(0, regW0).getUnsignedValue());
		assertEquals(new BigInteger("0800", 16),
			regs.getViewValue(0, regW1).getUnsignedValue());

		long scratch =
			emulationPlugin.emulate(trace, TraceSchedule.parse("0:t0-1"), TaskMonitor.DUMMY);

		assertEquals(new BigInteger("000102", 16),
			regs.getViewValue(scratch, regPC).getUnsignedValue());
		assertEquals(new BigInteger("1234", 16),
			regs.getViewValue(scratch, regW0).getUnsignedValue());
		assertEquals(new BigInteger("0800", 16),
			regs.getViewValue(scratch, regW1).getUnsignedValue());
	}
}
