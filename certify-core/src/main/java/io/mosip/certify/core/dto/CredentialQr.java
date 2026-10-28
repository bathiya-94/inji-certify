/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PixelPass text to render as a QR; not part of the signed credential. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialQr {
    private String encoding;
    private String data;
}
