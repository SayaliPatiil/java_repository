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
package ghidra.program.model.pcode;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.util.Msg;

/**
 * 
 *
 * All references (per function) to a single global variable
 */
public class HighGlobal extends HighVariable {

	private HighSymbol symbol;

	/**
	 * Constructor for use with restoreXml
	 * @param high is the HighFunction this global is accessed by
	 */
	public HighGlobal(HighFunction high) {
		super(high);
	}

	public HighGlobal(HighSymbol sym, Varnode vn, Varnode[] inst) {
		super(sym.getName(), sym.getDataType(), vn, inst, sym.getHighFunction());
		symbol = sym;
	}

	@Override
	public HighSymbol getSymbol() {
		return symbol;
	}

	@Override
	public void decode(Decoder decoder) throws DecoderException {
//		int el = decoder.openElement(ElementId.ELEM_HIGH);
		long symref = 0;
		offset = -1;
		for (;;) {
			int attribId = decoder.getNextAttributeId();
			if (attribId == 0) {
				break;
			}
			if (attribId == AttributeId.ATTRIB_OFFSET.id()) {
				offset = (int) decoder.readSignedInteger();
			}
			else if (attribId == AttributeId.ATTRIB_SYMREF.id()) {
				symref = decoder.readUnsignedInteger();
			}
		}
		decodeInstances(decoder);
		if (symref != 0) {
			symbol = function.getGlobalSymbolMap().getSymbol(symref);
		}
		else {
			Msg.warn(this, "Missing symref attribute in <high> tag");
		}
		if (symbol == null) {	// If we don't already have symbol, synthesize it
			DataType symbolType;
			int symbolSize;
			if (offset < 0) {		// Variable type and size matches symbol
				symbolType = type;
				symbolSize = getSize();
			}
			else {
				symbolType = null;
				symbolSize = -1;
			}
			GlobalSymbolMap globalMap = function.getGlobalSymbolMap();
			symbol = globalMap.populateSymbol(symref, symbolType, symbolSize);
			if (symbol == null) {
				Address addr = represent.getAddress();
				if (offset > 0) {
					addr = addr.subtract(offset);
				}
				symbol = globalMap.newSymbol(symref, addr, symbolType, symbolSize);
				if (symbol == null) {
					throw new DecoderException("Bad global storage: " + addr.toString());
				}
			}
		}
		if (offset < 0) {
			name = symbol.getName();
		}
		symbol.setHighVariable(this);

//		decoder.closeElement(el);
	}
}
