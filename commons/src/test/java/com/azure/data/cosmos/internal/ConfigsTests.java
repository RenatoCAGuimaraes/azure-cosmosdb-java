/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.azure.data.cosmos.internal;

import com.azure.data.cosmos.directconnectivity.Protocol;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigsTests {

    @Test(groups = { "unit" })
    public void maxHttpHeaderSize() {
        Configs config = new Configs();
        assertThat(config.getMaxHttpHeaderSize()).isEqualTo(32 * 1024);
    }

    @Test(groups = { "unit" })
    public void maxHttpBodyLength() {
        Configs config = new Configs();
        assertThat(config.getMaxHttpBodyLength()).isEqualTo(6 * 1024 * 1024);
    }

    @Test(groups = { "unit" })
    public void getProtocol() {
        Configs config = new Configs();
        assertThat(config.getProtocol()).isEqualTo(Protocol.valueOf(System.getProperty("cosmos.directModeProtocol", "TCP").toUpperCase()));
    }

    @Test(groups = { "unit" })
    public void getDirectHttpsMaxConnectionLimit() {
        Configs config = new Configs();
        assertThat(config.getDirectHttpsMaxConnectionLimit()).isEqualTo(Runtime.getRuntime().availableProcessors() * 500);
    }
}