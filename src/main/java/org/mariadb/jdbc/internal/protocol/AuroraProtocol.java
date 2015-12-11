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

Copyright (c) 2009-2011, Marcus Eriksson

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
import org.mariadb.jdbc.internal.util.ExceptionMapper;
import org.mariadb.jdbc.internal.util.dao.QueryException;
import org.mariadb.jdbc.internal.query.MariaDbQuery;
import org.mariadb.jdbc.internal.queryresults.SelectQueryResult;
import org.mariadb.jdbc.internal.failover.impl.AuroraListener;
import org.mariadb.jdbc.internal.failover.tools.SearchFilter;
import org.threadly.util.Clock;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class AuroraProtocol extends MastersSlavesProtocol {
    public AuroraProtocol(final UrlParser url, final ReentrantLock lock) {
        super(url, lock);
    }

    /**
     * Connect aurora probable master.
     * Aurora master change in time. The only way to check that a server is a master is to asked him.
     *
     * @param listener aurora failover to call back if master is found
     * @param probableMaster probable master host
     * @param blacklist current blacklist
     * @param searchFilter search filter
     * @throws QueryException exception
     */
    public static void searchProbableMaster(AuroraListener listener, HostAddress probableMaster, Map<HostAddress, Long> blacklist,
                                            SearchFilter searchFilter) throws QueryException {
        /*if (log.isDebugEnabled()) {
            log.debug("searching for master:" + searchFilter.isSearchForMaster() + " replica:" + searchFilter.isSearchForSlave()
            + " address:" + probableMaster + " blacklist:" + blacklist.keySet());
        }*/
        AuroraProtocol protocol = getNewProtocol(listener.getProxy(), listener.getUrlParser());
        try {

            protocol.setHostAddress(probableMaster);
            //if (log.isTraceEnabled()) log.trace("trying to connect to " + protocol.getHostAddress());
            protocol.connect();
            //if (log.isTraceEnabled()) log.trace("connected to " + protocol.getHostAddress());

            if (searchFilter.isSearchForMaster() && protocol.isMasterConnection()) {
                searchFilter.setSearchForMaster(false);
                protocol.setMustBeMasterConnection(true);
                listener.foundActiveMaster(protocol);
            } else if (searchFilter.isSearchForSlave() && !protocol.isMasterConnection()) {
                searchFilter.setSearchForSlave(false);
                protocol.setMustBeMasterConnection(false);
                listener.foundActiveSecondary(protocol);
            } else {
                //if (log.isDebugEnabled())
                //    log.debug("close connection because unused : " + protocol.getHostAddress());
                protocol.close();
                protocol = getNewProtocol(listener.getProxy(), listener.getUrlParser());
            }

        } catch (QueryException e) {
            blacklist.put(protocol.getHostAddress(), Clock.accurateForwardProgressingMillis());
//            if (log.isDebugEnabled())
//                log.debug("Could not connect to " + protocol.currentHost + " searching for master : " + searchFilter.isSearchForMaster()
// + " for replica :" + searchFilter.isSearchForSlave() + " error:" + e.getMessage());
        }
    }

    /**
     * loop until found the failed connection.
     *
     * @param listener     current failover
     * @param addresses    list of HostAddress to loop
     * @param blacklist    current blacklist
     * @param searchFilter search parameter
     * @throws QueryException if not found
     */
    public static void loop(AuroraListener listener, final List<HostAddress> addresses, Map<HostAddress, Long> blacklist, SearchFilter searchFilter)
            throws QueryException {
//        if (log.isDebugEnabled()) {
//            log.debug("searching for master:" + searchFilter.isSearchForMaster() + " replica:" + searchFilter.isSearchForSlave()
// + " addresses:" + addresses);
//        }

        AuroraProtocol protocol;
        Deque<HostAddress> loopAddresses = new ArrayDeque<>(addresses);
        int maxConnectionTry = listener.getRetriesAllDown();
        QueryException lastQueryException = null;

        while (!loopAddresses.isEmpty() || (!searchFilter.isUniqueLoop() && maxConnectionTry > 0)) {
            protocol = getNewProtocol(listener.getProxy(), listener.getUrlParser());

            if (listener.isExplicitClosed() || (!listener.isSecondaryHostFail() && !listener.isMasterHostFail())) {
                return;
            }
            maxConnectionTry--;

            try {
                protocol.setHostAddress(loopAddresses.removeFirst());

                protocol.connect();
                if (listener.isExplicitClosed()) {
                    protocol.close();
                    return;
                }
                blacklist.remove(protocol.getHostAddress());

                if (searchFilter.isSearchForMaster() && protocol.isMasterConnection()) {
                    if (foundMaster(listener, protocol, searchFilter)) {
                        return;
                    }
                } else if (searchFilter.isSearchForSlave() && !protocol.isMasterConnection()) {
                    if (foundSecondary(listener, protocol, searchFilter)) {
                        return;
                    }

                    HostAddress probableMasterHost = listener.searchByStartName(protocol, listener.getUrlParser().getHostAddresses());
                    if (probableMasterHost != null) {
                        loopAddresses.remove(probableMasterHost);
                        AuroraProtocol.searchProbableMaster(listener, probableMasterHost, blacklist, searchFilter);
                        if (!searchFilter.isSearchForMaster()) {
                            return;
                        }
                    }
                } else {
                    protocol.close();
                }
            } catch (QueryException e) {
                lastQueryException = e;
                blacklist.put(protocol.getHostAddress(), Clock.accurateForwardProgressingMillis());
            }

            if (!searchFilter.isSearchForMaster() && !searchFilter.isSearchForSlave()) {
                return;
            }

            //loop is set so
            if (loopAddresses.isEmpty() && !searchFilter.isUniqueLoop() && maxConnectionTry > 0) {
                loopAddresses = new ArrayDeque<>(addresses);
                listener.checkMasterStatus(searchFilter);
            }
        }

        if (searchFilter.isSearchForMaster() || searchFilter.isSearchForSlave()) {
            String error = "No active connection found for replica";
            if (searchFilter.isSearchForMaster())  {
                error = "No active connection found for master";
            }
            if (lastQueryException != null) {
                throw new QueryException(error, lastQueryException.getErrorCode(), lastQueryException.getSqlState(), lastQueryException);
            }
            throw new QueryException(error);
        }
    }

    private static boolean foundMaster(AuroraListener listener, AuroraProtocol protocol, SearchFilter searchFilter) {
        protocol.setMustBeMasterConnection(true);
        searchFilter.setSearchForMaster(false);
        listener.foundActiveMaster(protocol);
        if (!searchFilter.isSearchForSlave()) {
            return true;
        } else {
            if (listener.isExplicitClosed()
                    || searchFilter.isFineIfFoundOnlyMaster()
                    || !listener.isSecondaryHostFail()) {
                return true;
            }
        }
        return false;
    }

    private static boolean foundSecondary(AuroraListener listener, AuroraProtocol protocol, SearchFilter searchFilter) throws QueryException {
        searchFilter.setSearchForSlave(false);
        protocol.setMustBeMasterConnection(false);
        listener.foundActiveSecondary(protocol);
        if (!searchFilter.isSearchForMaster()) {
            return true;
        } else {
            if (listener.isExplicitClosed()
                    || searchFilter.isFineIfFoundOnlySlave()
                    || !listener.isMasterHostFail()) {
                return true;
            }


        }
        return false;
    }

    /**
     * Initilize new protocol instance.
     * @param proxy proxy
     * @param urlParser connection string data's
     * @return new AuroraProtocol
     */
    public static AuroraProtocol getNewProtocol(FailoverProxy proxy, UrlParser urlParser) {
        AuroraProtocol newProtocol = new AuroraProtocol(urlParser, proxy.lock);
        newProtocol.setProxy(proxy);
        return newProtocol;
    }

    @Override
    public boolean isMasterConnection() {
        return this.masterConnection;
    }

    /**
     * Aurora best way to check if a node is a master : is not in read-only mode.
     *
     * @return indicate if master has been found
     */
    @Override
    public boolean checkIfMaster() throws QueryException {
        proxy.lock.lock();
        try {
            SelectQueryResult queryResult = (SelectQueryResult) executeQuery(new MariaDbQuery("show global variables like 'innodb_read_only'"));
            if (queryResult != null) {
                queryResult.next();
                this.masterConnection = "OFF".equals(queryResult.getValueObject(1).getString());
            } else {
                this.masterConnection = false;
            }
            this.readOnly = !this.masterConnection;
            return this.masterConnection;

        } catch (SQLException sqle) {
            throw new QueryException("could not check the 'innodb_read_only' variable status on " + this.getHostAddress()
                    + " : " + sqle.getMessage(), -1, ExceptionMapper.SqlStates.CONNECTION_EXCEPTION.getSqlState(), sqle);
        } catch (IOException ioe) {
            throw new QueryException("could not check the 'innodb_read_only' variable status on " + this.getHostAddress()
                    + " : " + ioe.getMessage(), -1, ExceptionMapper.SqlStates.CONNECTION_EXCEPTION.getSqlState(), ioe);
        } finally {
            proxy.lock.unlock();
        }
    }


}
