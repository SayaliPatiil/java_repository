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
package ghidra.app.util.bin.format.pdb2.pdbreader;

/**
 * This class is the version of {@link SectionContribution} for Microsoft v14.00 PDB.
 */
public class SectionContribution1400 extends SectionContribution {

	//==============================================================================================
	// Abstract Methods
	//==============================================================================================
	@Override
	void deserialize(PdbByteReader reader) throws PdbException {
		isect = reader.parseUnsignedShortVal();
		reader.parseBytes(2); // I think there is padding here.
		offset = reader.parseInt();
		length = reader.parseInt();
		characteristics = reader.parseUnsignedIntVal();
		imod = reader.parseUnsignedShortVal();
		reader.align4();
		dataCrc = reader.parseUnsignedIntVal();
		relocationCrc = reader.parseUnsignedIntVal();
		unknownSectionContributionField = reader.parseUnsignedIntVal();
	}

	@Override
	String dumpInternals() {
		StringBuilder builder = new StringBuilder();
		builder.append("isect: ");
		builder.append(isect);
		builder.append("\noffset: ");
		builder.append(offset);
		builder.append("\nlength: ");
		builder.append(length);
		builder.append(String.format("\ncharacteristics: 0X%08X", characteristics));
		builder.append("\nimod: ");
		builder.append(imod);
		builder.append("\ndataCrc: ");
		builder.append(dataCrc);
		builder.append("\nrelocationCrc: ");
		builder.append(relocationCrc);
		builder.append("\nunknownSectionContributionField: ");
		builder.append(unknownSectionContributionField);
		return builder.toString();
	}

}
