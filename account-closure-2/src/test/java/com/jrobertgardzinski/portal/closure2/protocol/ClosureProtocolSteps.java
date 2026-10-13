package com.jrobertgardzinski.portal.closure2.protocol;

import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.Outcome;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.Report;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.State;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The steps of {@code closure-protocol.feature}: the machine alone, with an account that only records
 * what it was asked. No part of the portal is here — what a part does with its share is the
 * business of {@code ../specs-2}, which the runner drives through the same {@link ClosureProtocol}.
 */
public class ClosureProtocolSteps {

    /** An account row that remembers whether it was asked to go and whether it went. */
    private static final class RecordingAccount implements ClosureProtocol.Account {
        boolean refuses;
        boolean askedToGo;
        boolean gone;
        boolean unlocked;

        @Override
        public void go() {
            askedToGo = true;
            if (refuses) {
                throw new IllegalStateException("the user row refuses to be deleted");
            }
            gone = true;
        }

        @Override
        public void unlock() {
            unlocked = true;
        }
    }

    private final RecordingAccount account = new RecordingAccount();
    private ClosureProtocol protocol;
    private Outcome last;

    @Given("a closure of alice's account was requested")
    public void a_closure_was_requested() {
        protocol = new ClosureProtocol(account);
    }

    @Given("the account will refuse to go")
    public void the_account_will_refuse_to_go() {
        account.refuses = true;
    }

    /** The canonical road to a state: every part reports, and the account goes or refuses. */
    @Given("the closure has reached {word}")
    public void the_closure_has_reached(String state) {
        switch (State.valueOf(state)) {
            case HIDING -> { }
            case DESTROYING -> everyPartReports(Report.HIDDEN);
            case RESTORING -> {
                account.refuses = true;
                everyPartReports(Report.HIDDEN);
            }
            case DONE -> {
                everyPartReports(Report.HIDDEN);
                everyPartReports(Report.DESTROYED);
            }
            case WITHDRAWN -> {
                account.refuses = true;
                everyPartReports(Report.HIDDEN);
                everyPartReports(Report.RESTORED);
            }
        }
        assertEquals(State.valueOf(state), protocol.state(), "the canonical road did not lead there");
    }

    @Given("{word} has reported its share {word}")
    public void a_part_has_reported(String part, String report) {
        last = protocol.report(reportOf(report), part);
        assertEquals(Outcome.ACCEPTED, last, part + " could not report " + report + " on the way");
    }

    @When("{word} reports its share {word}")
    public void a_part_reports(String part, String report) {
        last = protocol.report(reportOf(report), part);
    }

    @When("{word} reports its share {word} again")
    public void a_part_reports_again(String part, String report) {
        a_part_reports(part, report);
    }

    @When("the closure is given up on")
    public void the_closure_is_given_up_on() {
        last = protocol.givenUp();
    }

    @Then("the closure is {word}")
    public void the_closure_is(String state) {
        assertEquals(State.valueOf(state), protocol.state());
    }

    @Then("the closure is still {word}")
    public void the_closure_is_still(String state) {
        assertEquals(State.valueOf(state), protocol.state(), "a refused report moved the closure");
    }

    @Then("the report is accepted")
    public void the_report_is_accepted() {
        assertEquals(Outcome.ACCEPTED, last);
    }

    @Then("the report is nothing new")
    public void the_report_is_nothing_new() {
        assertEquals(Outcome.NOTHING_NEW, last);
    }

    @Then("the report is refused")
    public void the_report_is_refused() {
        assertEquals(Outcome.REFUSED, last);
    }

    @Then("the account has not been asked to go")
    public void the_account_has_not_been_asked_to_go() {
        assertFalse(account.askedToGo, "the account was asked to go before every part had hidden its share");
    }

    @Then("the account was asked to go")
    public void the_account_was_asked_to_go() {
        assertTrue(account.askedToGo);
    }

    @Then("the account is gone")
    public void the_account_is_gone() {
        assertTrue(account.gone);
    }

    @Then("the account is still there")
    public void the_account_is_still_there() {
        assertFalse(account.gone);
    }

    @Then("the account was unlocked")
    public void the_account_was_unlocked() {
        assertTrue(account.unlocked, "the closure turned back and left the account locked");
    }

    private void everyPartReports(Report report) {
        for (String part : ClosureProtocol.PARTS) {
            protocol.report(report, part);
        }
    }

    private static Report reportOf(String word) {
        return Report.valueOf(word.toUpperCase(Locale.ROOT));
    }
}
