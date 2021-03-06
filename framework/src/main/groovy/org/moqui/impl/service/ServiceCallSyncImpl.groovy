/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.service

import javax.transaction.Transaction

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.service.ServiceCallSync

import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.service.ServiceException
import org.moqui.context.ArtifactAuthorizationException

class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ServiceCallSyncImpl.class)

    protected boolean requireNewTransaction = false
    /* not supported by Atomikos/etc right now, consider for later: protected int transactionIsolation = -1 */

    protected boolean multi = false

    ServiceCallSyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSync name(String serviceName) { this.setServiceName(serviceName); return this }

    @Override
    ServiceCallSync name(String v, String n) { path = null; verb = v; noun = n; return this }

    @Override
    ServiceCallSync name(String p, String v, String n) { path = p; verb = v; noun = n; return this }

    @Override
    ServiceCallSync parameters(Map<String, ?> map) { parameters.putAll(map); return this }

    @Override
    ServiceCallSync parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallSync requireNewTransaction(boolean rnt) { this.requireNewTransaction = rnt; return this }

    @Override
    ServiceCallSync multi(boolean mlt) { this.multi = mlt; return this }

    /* not supported by Atomikos/etc right now, consider for later:
    @Override
    ServiceCallSync transactionIsolation(int ti) { this.transactionIsolation = ti; return this }
    */

    @Override
    Map<String, Object> call() {
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName())
        ExecutionContextImpl eci = (ExecutionContextImpl) sfi.ecfi.executionContext

        Collection<String> inParameterNames = null
        if (sd != null) {
            inParameterNames = sd.getInParameterNames()
        } else {
            EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(noun)
            if (ed != null) inParameterNames = ed.getAllFieldNames()
        }
        if (multi) {
            for (int i = 0; ; i++) {
                Map<String, Object> currentParms = new HashMap()
                for (String ipn in inParameterNames) {
                    String key = ipn + "_" + i
                    if (this.parameters.containsKey(key)) {
                        currentParms.put(ipn, this.parameters.get(key))
                    } else if (this.parameters.containsKey(ipn)) {
                        currentParms.put(ipn, this.parameters.get(ipn))
                    }
                }
                // if the map stayed empty we have no parms, so we're done
                if (currentParms.size() == 0) break
                // call the service, ignore the result...
                callSingle(currentParms, sd, eci)
                // ... and break if there are any errors
                if (eci.message.errors) break
            }
        } else {
            return callSingle(this.parameters, sd, eci)
        }
    }

    Map<String, Object> callSingle(Map<String, Object> currentParameters, ServiceDefinition sd, ExecutionContextImpl eci) {
        long callStartTime = System.currentTimeMillis()

        // default to require the "All" authz action, and for special verbs default to something more appropriate
        String authzAction = "AUTHZA_ALL"
        switch (verb) {
            case "create": authzAction = "AUTHZA_CREATE"; break
            case "update": authzAction = "AUTHZA_UPDATE"; break
            case "delete": authzAction = "AUTHZA_DELETE"; break
            case "view":
            case "find": authzAction = "AUTHZA_VIEW"; break
        }
        eci.getArtifactExecution().push(new ArtifactExecutionInfoImpl(getServiceName(), "AT_SERVICE", authzAction),
                (sd != null && sd.getAuthenticate() == "true"))
        if (sd != null && sd.getAuthenticate() == "anonymous-all") {
            eci.getArtifactExecution().setAnonymousAuthorizedAll()
        } else if (sd != null && sd.getAuthenticate() == "anonymous-view") {
            eci.getArtifactExecution().setAnonymousAuthorizedView()
        }
        // NOTE: don't require authz if the service def doesn't authenticate
        // NOTE: if no sd then requiresAuthz is false, ie let the authz get handled at the entity level (but still put
        //     the service on the stack)

        if (sd == null) {
            // if no path, verb is create|update|delete and noun is a valid entity name, do an implicit entity-auto
            if (!path && ("create".equals(verb) || "update".equals(verb) || "delete".equals(verb) || "store".equals(verb)) &&
                    sfi.getEcfi().getEntityFacade().getEntityDefinition(noun) != null) {
                Map result = runImplicitEntityAuto(currentParameters, eci)

                long endTime = System.currentTimeMillis()
                if (logger.traceEnabled) logger.trace("Finished call to service [${getServiceName()}] in ${(endTime-callStartTime)/1000} seconds")
                sfi.getEcfi().countArtifactHit("service", "entity-implicit", getServiceName(), currentParameters, callStartTime, endTime, null)

                eci.artifactExecution.pop()
                return result
            } else {
                throw new ServiceException("Could not find service with name [${getServiceName()}]")
            }
        }

        String serviceType = sd.getServiceType()
        if ("interface".equals(serviceType)) throw new ServiceException("Cannot run interface service [${getServiceName()}]")

        ServiceRunner sr = sfi.getServiceRunner(serviceType)
        if (sr == null) throw new ServiceException("Could not find service runner for type [${serviceType}] for service [${getServiceName()}]")

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (requireNewTransaction) {
            // if the setting for this service call is in place, use it regardless of the settings on the service
            pauseResumeIfNeeded = true
        } else {
            if (sd.getTxIgnore()) {
                beginTransactionIfNeeded = false
            } else if (sd.getTxForceNew()) {
                pauseResumeIfNeeded = true
            }
        }

        // TODO (future) sd.serviceNode."@semaphore"

        sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-validate")

        // validation
        sd.convertValidateCleanParameters(currentParameters, eci)
        // if error(s) in parameters, return now with no results
        if (eci.getMessage().getErrors().size() > 0) return null

        boolean userLoggedIn = false
        TransactionFacade tf = sfi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        Map<String, Object> result = null
        try {
            // authentication
            sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-auth")
            // always try to login the user if parameters are specified
            String userId = currentParameters.authUserAccount?.userId ?: currentParameters.authUsername
            String password = currentParameters.authUserAccount?.currentPassword ?: currentParameters.authPassword
            String tenantId = currentParameters.authTenantId
            if (userId && password) userLoggedIn = eci.getUser().loginUser(userId, password, tenantId)
            if (sd.getAuthenticate() == "true" && !eci.getUser().getUserId())
                eci.getMessage().addError("Authentication required for service [${getServiceName()}]")

            // if error in auth or for other reasons, return now with no results
            if (eci.getMessage().getErrors().size() > 0) return null

            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ? tf.begin(sd.getTxTimeout()) : false
            try {
                sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-service")
                sfi.registerTxSecaRules(getServiceName(), currentParameters)
                result = sr.runService(sd, currentParameters)
                sfi.runSecaRules(getServiceName(), currentParameters, result, "post-service")
                // if we got any errors added to the message list in the service, rollback for that too
                if (eci.getMessage().getErrors().size() > 0) {
                    tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (message): " + eci.getMessage().getErrors().get(0), null)
                }
            } catch (ArtifactAuthorizationException e) {
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (Throwable)", t)
                // add all exception messages to the error messages list
                eci.getMessage().addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.getMessage().addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                sfi.runSecaRules(getServiceName(), currentParameters, result, "post-commit")
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after call to service [${getServiceName()}]")
            }
            try {
                if (userLoggedIn) eci.getUser().logoutUser()
            } catch (Throwable t) {
                logger.error("Error logging out user after call to service [${getServiceName()}]")
            }

            long endTime = System.currentTimeMillis()
            sfi.getEcfi().countArtifactHit("service", serviceType, getServiceName(), currentParameters, callStartTime, endTime, null)

            if (logger.traceEnabled) logger.trace("Finished call to service [${getServiceName()}] in ${(endTime-callStartTime)/1000} seconds" + (eci.message.errors ? " with ${eci.message.errors.size()} error messages" : ", was successful"))
        }

        // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally clause because if there is an error this will help us know how we got there
        eci.getArtifactExecution().pop()

        return result
    }

    protected Map<String, Object> runImplicitEntityAuto(Map<String, Object> currentParameters, ExecutionContextImpl eci) {
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-validate")
        sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-auth")

        TransactionFacade tf = sfi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        Map<String, Object> result = new HashMap()
        try {
            if (requireNewTransaction && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(null)
            try {
                sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-service")
                sfi.registerTxSecaRules(getServiceName(), currentParameters)

                EntityDefinition ed = sfi.getEcfi().getEntityFacade().getEntityDefinition(noun)
                switch (verb) {
                    case "create": EntityAutoServiceRunner.createEntity(sfi, ed, currentParameters, result, null); break
                    case "update": EntityAutoServiceRunner.updateEntity(sfi, ed, currentParameters, result, null, null); break
                    case "delete": EntityAutoServiceRunner.deleteEntity(sfi, ed, currentParameters); break
                    case "store": EntityAutoServiceRunner.storeEntity(sfi, ed, currentParameters, result, null); break
                }

                sfi.runSecaRules(getServiceName(), currentParameters, result, "post-service")
            } catch (ArtifactAuthorizationException e) {
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                logger.error("Error running service [${getServiceName()}]", t)
                tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (Throwable)", t)
                // add all exception messages to the error messages list
                eci.getMessage().addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.getMessage().addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                sfi.runSecaRules(getServiceName(), currentParameters, result, "post-commit")
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (suspendedTransaction) tf.resume()
        }
        return result
    }
}
