/**
 * Copyright 2016-2018 The Reaktivity Project
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
package org.reaktivity.nukleus.sse.internal.stream;

public final class ServerHandshake
{
    private final long networkRouteId;
    private final long networkId;
    private final String networkName;
    private final long correlationId;
    private final long applicationRouteId;
    private final boolean timestampRequested;

    public ServerHandshake(
        long networkRouteId,
        long networkId,
        String networkName,
        long correlationId,
        long applicationRouteId,
        boolean timestampRequested)
    {
        this.networkRouteId = networkRouteId;
        this.networkId = networkId;
        this.networkName = networkName;
        this.correlationId = correlationId;
        this.applicationRouteId = applicationRouteId;
        this.timestampRequested = timestampRequested;
    }

    public long networkRouteId()
    {
        return networkRouteId;
    }

    public long networkId()
    {
        return networkId;
    }

    public String networkName()
    {
        return networkName;
    }

    public long correlationId()
    {
        return correlationId;
    }

    public long applicationRouteId()
    {
        return applicationRouteId;
    }

    public boolean timestampRequested()
    {
        return timestampRequested;
    }
}
