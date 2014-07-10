/*
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
 *
 * Groupon licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.payment.core.sm;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.payment.dao.PaymentDao;
import org.killbill.billing.payment.dao.PaymentMethodModelDao;
import org.killbill.billing.payment.dao.PaymentModelDao;
import org.killbill.billing.payment.dao.PaymentTransactionModelDao;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;

import com.google.common.collect.ImmutableList;

public class PaymentAutomatonDAOHelper {

    protected final PaymentStateContext paymentStateContext;
    protected final DateTime utcNow;
    protected final InternalCallContext internalCallContext;
    protected final PaymentStateMachineHelper paymentSMHelper;

    protected final PaymentDao paymentDao;

    private final OSGIServiceRegistration<PaymentPluginApi> pluginRegistry;

    // Used to build new payments and transactions
    public PaymentAutomatonDAOHelper(final PaymentStateContext paymentStateContext,
                                     final DateTime utcNow, final PaymentDao paymentDao,
                                     final OSGIServiceRegistration<PaymentPluginApi> pluginRegistry,
                                     final InternalCallContext internalCallContext,
                                     final PaymentStateMachineHelper paymentSMHelper) throws PaymentApiException {
        this.paymentStateContext = paymentStateContext;
        this.utcNow = utcNow;
        this.paymentDao = paymentDao;
        this.pluginRegistry = pluginRegistry;
        this.internalCallContext = internalCallContext;
        this.paymentSMHelper = paymentSMHelper;
    }

    public void createNewPaymentTransaction() throws PaymentApiException {

        final PaymentTransactionModelDao paymentTransactionModelDao;
        final List<PaymentTransactionModelDao> existingTransactions;
        if (paymentStateContext.getPaymentId() == null) {
            final PaymentModelDao newPaymentModelDao = buildNewPaymentModelDao();
            final PaymentTransactionModelDao newPaymentTransactionModelDao = buildNewPaymentTransactionModelDao(newPaymentModelDao.getId());

            existingTransactions = ImmutableList.of();
            final PaymentModelDao paymentModelDao = paymentDao.insertPaymentWithFirstTransaction(newPaymentModelDao, newPaymentTransactionModelDao, internalCallContext);
            paymentTransactionModelDao = paymentDao.getTransactionsForPayment(paymentModelDao.getId(), internalCallContext).get(0);

        } else {
            existingTransactions = paymentDao.getTransactionsForPayment(paymentStateContext.getPaymentId(), internalCallContext);
            if (existingTransactions.isEmpty()) {
                throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_SUCCESS_PAYMENT, paymentStateContext.getPaymentId());
            }
            if (paymentStateContext.getCurrency() != null && existingTransactions.get(0).getCurrency() != paymentStateContext.getCurrency()) {
                throw new PaymentApiException(ErrorCode.PAYMENT_INVALID_PARAMETER, "currency", " should be " + existingTransactions.get(0).getCurrency() + " to match other existing transactions");
            }

            final PaymentTransactionModelDao newPaymentTransactionModelDao = buildNewPaymentTransactionModelDao(paymentStateContext.getPaymentId());
            paymentTransactionModelDao = paymentDao.updatePaymentWithNewTransaction(paymentStateContext.getPaymentId(), newPaymentTransactionModelDao, internalCallContext);
        }
        // Update the context
        paymentStateContext.setPaymentTransactionModelDao(paymentTransactionModelDao);
        paymentStateContext.setOnLeavingStateExistingTransactions(existingTransactions);
    }

    public void processPaymentInfoPlugin(final TransactionStatus paymentStatus, @Nullable final PaymentTransactionInfoPlugin paymentInfoPlugin,
                                         final String currentPaymentStateName) {
        final BigDecimal processedAmount = paymentInfoPlugin == null ? null : paymentInfoPlugin.getAmount();
        final Currency processedCurrency = paymentInfoPlugin == null ? null : paymentInfoPlugin.getCurrency();
        final String gatewayErrorCode = paymentInfoPlugin == null ? null : paymentInfoPlugin.getGatewayErrorCode();
        final String gatewayErrorMsg = paymentInfoPlugin == null ? null : paymentInfoPlugin.getGatewayError();

        final String lastSuccessPaymentState = paymentSMHelper.isSuccessState(currentPaymentStateName) ? currentPaymentStateName : null;
        paymentDao.updatePaymentAndTransactionOnCompletion(paymentStateContext.getPaymentId(),
                                                                 currentPaymentStateName,
                                                                 lastSuccessPaymentState,
                                                                 paymentStateContext.getPaymentTransactionModelDao().getId(),
                                                                 paymentStatus,
                                                                 processedAmount,
                                                                 processedCurrency,
                                                                 gatewayErrorCode,
                                                                 gatewayErrorMsg,
                                                                 internalCallContext);

        // Update the context
        paymentStateContext.setPaymentTransactionModelDao(paymentDao.getPaymentTransaction(paymentStateContext.getPaymentTransactionModelDao().getId(), internalCallContext));
    }

    public UUID getDefaultPaymentMethodId() throws PaymentApiException {
        final UUID paymentMethodId = paymentStateContext.getAccount().getPaymentMethodId();
        if (paymentMethodId == null) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_DEFAULT_PAYMENT_METHOD, paymentStateContext.getAccount().getId());
        }
        return paymentMethodId;
    }

    public PaymentPluginApi getPaymentProviderPlugin() throws PaymentApiException {

        final UUID paymentMethodId = paymentStateContext.getPaymentMethodId();
        final PaymentMethodModelDao methodDao = paymentDao.getPaymentMethodIncludedDeleted(paymentMethodId, internalCallContext);
        if (methodDao == null) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_PAYMENT_METHOD, paymentMethodId);
        }
        return getPaymentPluginApi(methodDao.getPluginName());
    }

    public PaymentModelDao getPayment() throws PaymentApiException {
        final PaymentModelDao paymentModelDao;
        paymentModelDao = paymentDao.getPayment(paymentStateContext.getPaymentId(), internalCallContext);
        if (paymentModelDao == null) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_PAYMENT, paymentStateContext.getPaymentId());
        }
        return paymentModelDao;
    }

    private PaymentModelDao buildNewPaymentModelDao() {
        final DateTime createdDate = utcNow;
        final DateTime updatedDate = utcNow;

        return new PaymentModelDao(createdDate,
                                   updatedDate,
                                   paymentStateContext.getAccount().getId(),
                                   paymentStateContext.getPaymentMethodId(),
                                   paymentStateContext.getPaymentExternalKey());
    }

    private PaymentTransactionModelDao buildNewPaymentTransactionModelDao(final UUID paymentId) {
        final DateTime createdDate = utcNow;
        final DateTime updatedDate = utcNow;
        final DateTime effectiveDate = utcNow;
        final String gatewayErrorCode = null;
        final String gatewayErrorMsg = null;

        return new PaymentTransactionModelDao(createdDate,
                                              updatedDate,
                                              paymentStateContext.getAttemptId(),
                                              paymentStateContext.getPaymentTransactionExternalKey(),
                                              paymentId,
                                              paymentStateContext.getTransactionType(),
                                              effectiveDate,
                                              TransactionStatus.UNKNOWN,
                                              paymentStateContext.getAmount(),
                                              paymentStateContext.getCurrency(),
                                              gatewayErrorCode,
                                              gatewayErrorMsg);
    }

    private PaymentPluginApi getPaymentPluginApi(final String pluginName) throws PaymentApiException {
        final PaymentPluginApi pluginApi = pluginRegistry.getServiceForName(pluginName);
        if (pluginApi == null) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_PAYMENT_PLUGIN, pluginName);
        }
        return pluginApi;
    }
}