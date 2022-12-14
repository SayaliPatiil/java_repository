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
package ghidra.util.database.err;

/**
 * Exception when a custom codec is required
 */
@SuppressWarnings("serial")
public class NoDefaultCodecException extends RuntimeException {
	public NoDefaultCodecException() {
		super();
	}

	public NoDefaultCodecException(String message) {
		super(message);
	}

	public NoDefaultCodecException(String message, Throwable cause) {
		super(message, cause);
	}
}
