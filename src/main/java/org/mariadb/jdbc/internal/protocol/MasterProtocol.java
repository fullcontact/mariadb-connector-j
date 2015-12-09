/*
MariaDB Client for Java

Copyright (c) 2012 Monty Program Ab.

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:


Copyright (c) 2009-2011, Marcus Eriksson, Stephane Giron, Marc Isambart, Trond Norbye

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

package org.mariadb.jdbc.internal.protocol;

import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.UrlParser;
import org.mariadb.jdbc.internal.failover.FailoverProxy;
import org.mariadb.jdbc.internal.util.dao.QueryException;
import org.mariadb.jdbc.internal.failover.Listener;
import org.mariadb.jdbc.internal.failover.tools.SearchFilter;
import org.threadly.util.Clock;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


public class MasterProtocol extends AbstractQueryProtocol {

    /**
     * Get a protocol instance.
     *
     * @param urlParser connection URL infos
     * @param lock the lock for thread synchronisation
     */

    public MasterProtocol(final UrlParser urlParser, final ReentrantLock lock) {
        super(urlParser, lock);
    }

    /**
     * Get new instance.
     *
     * @param proxy proxy
     * @param urlParser url connection object
     * @return new instance
     */
    public static MasterProtocol getNewProtocol(FailoverProxy proxy, UrlParser urlParser) {
        MasterProtocol newProtocol = new MasterProtocol(urlParser, proxy.lock);
        newProtocol.setProxy(proxy);
        return newProtocol;
    }

    /**
     * loop until found the failed connection.
     *
     * @param listener current failover
     * @param addresses list of HostAddress to loop
     * @param blacklist current blacklist
     * @param searchFilter search parameter
     * @throws QueryException if not found
     */
    public static void loop(Listener listener, final List<HostAddress> addresses, Map<HostAddress, Long> blacklist,
                            SearchFilter searchFilter) throws QueryException {

        MasterProtocol protocol;
        List<HostAddress> loopAddresses = new LinkedList<>(addresses);
        int maxConnectionTry = listener.getRetriesAllDown();
        QueryException lastQueryException = null;
        while (!loopAddresses.isEmpty() || (!searchFilter.isUniqueLoop() && maxConnectionTry > 0)) {
            protocol = getNewProtocol(listener.getProxy(), listener.getUrlParser());

            if (listener.isExplicitClosed()) {
                return;
            }
            maxConnectionTry--;

            try {
                protocol.setHostAddress(loopAddresses.get(0));
                loopAddresses.remove(0);

                protocol.connect();
                if (listener.isExplicitClosed()) {
                    protocol.close();
                    return;
                }
                blacklist.remove(protocol.getHostAddress());
                listener.foundActiveMaster(protocol);
                return;

            } catch (QueryException e) {
                blacklist.put(protocol.getHostAddress(), Clock.accurateForwardProgressingMillis());
                lastQueryException = e;
            }

            if (loopAddresses.isEmpty() && !searchFilter.isUniqueLoop() && maxConnectionTry > 0) {
                loopAddresses = new LinkedList<>(addresses);
            }
        }
        if (lastQueryException != null) {
            throw new QueryException("No active connection found for master : " + lastQueryException.getMessage(),
                    lastQueryException.getErrorCode(), lastQueryException.getSqlState(), lastQueryException);
        }
        throw new QueryException("No active connection found for master");
    }

}
