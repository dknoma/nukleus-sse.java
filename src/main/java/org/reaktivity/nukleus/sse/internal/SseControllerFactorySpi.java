/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.sse.internal;

import org.reaktivity.nukleus.Configuration;
import org.reaktivity.nukleus.ControllerBuilder;
import org.reaktivity.nukleus.ControllerFactorySpi;

public final class SseControllerFactorySpi implements ControllerFactorySpi<SseController>
{
    @Override
    public String name()
    {
        return "sse";
    }

    @Override
    public Class<SseController> kind()
    {
        return SseController.class;
    }

    @Override
    public SseController create(
        Configuration config,
        ControllerBuilder<SseController> builder)
    {
        return builder.setFactory(SseController::new)
                      .build();
    }
}
