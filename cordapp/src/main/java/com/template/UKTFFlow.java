package com.template;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;

import java.security.PublicKey;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;


@InitiatingFlow
@StartableByRPC
public class UKTFFlow extends FlowLogic<Void> {
    private final Integer bondValue;
    /* the node running the flow is the exporter (this one is the bank, that need to sign the transaction) */
    private final Party bank;
//    private final Party ukef;

    private final Step GENERATING_EXP_TRANSACTION = new Step("Generating transaction based on new Bond Request.");
//    private final Step GENERATING_BANK_TRANSACTION = new Step("Generating transaction based on Bank activity.");
//    private final Step GENERATING_UKEF_TRANSACTION = new Step("Generating transaction based on UKEF activity.");
    private final Step VERIFYING_TRANSACTION = new Step("Verifying contract constraints.");
    private final Step SIGNING_TRANSACTION = new Step("Signing transaction with our private key.");
    private final Step GATHERING_SIGS = new Step("Gathering the counterparty's signature.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final Step FINALISING_TRANSACTION = new Step("Obtaining notary signature and recording transaction.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };


    private final ProgressTracker progressTracker = new ProgressTracker(
            GENERATING_EXP_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
    );

    public UKTFFlow(Integer bondValue, Party bank
//            , Party ukef
    ) {
        this.bondValue = bondValue;
        this.bank = bank;
//        this.ukef = ukef;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public Void call() throws FlowException {

        //Notary for the transaction
        final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        //Stage1 - generate transaction
        progressTracker.setCurrentStep(GENERATING_EXP_TRANSACTION);

        UKTFState outputState = new UKTFState(new UniqueIdentifier(), bondValue, getOurIdentity(), bank);
        List<PublicKey> requiredSigners = ImmutableList.of(getOurIdentity().getOwningKey(), bank.getOwningKey());
        final Command<UKTFContract.Commands.Create> cmd = new Command<>(new UKTFContract.Commands.Create(), requiredSigners); // create the command generating the transaction

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addOutputState(outputState, UKTFContract.UKTF_CONTRACT_ID)
                .addCommand(cmd);


        // Stage 2 - verifying trx
        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        // Stage 3 - signing trx
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);


        //Step 4 - gathering trx signs
        progressTracker.setCurrentStep(GATHERING_SIGS);

        //bank
        FlowSession bankSession = initiateFlow(bank);
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(
                partSignedTx , ImmutableList.of(bankSession), CollectSignaturesFlow.tracker()));


        //Step 5 - finalising
        progressTracker.setCurrentStep(FINALISING_TRANSACTION);

        subFlow(new FinalityFlow(fullySignedTx));

        return null;
    }


    @InitiatedBy(UKTFFlow.class)
    public static class UKTFBankFlow extends FlowLogic<Void> {

        private FlowSession exporter;

        public UKTFBankFlow(FlowSession exporter) {
            this.exporter = exporter;
        }

        @Suspendable
        @Override
        public Void call() throws FlowException {

            class SignExpTxFlow extends SignTransactionFlow {

                private SignExpTxFlow(FlowSession exporter, ProgressTracker progressTracker) {
                    super(exporter, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(require -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        require.using("This must be an UKTF transaction.", output instanceof UKTFState);
                        UKTFState bond = (UKTFState) output;
                        require.using("The UKTF bond's value can't be null.", bond.getBondValue() > 0);
                        return null;
                    });
                }
            }


            subFlow(new SignExpTxFlow(exporter, SignTransactionFlow.Companion.tracker()));

            return null;
        }
    }

}