package com.jrobertgardzinski.portal.closure;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * The portal's own specs, driven against the orchestrator and all three participants at once, in
 * one process, with nothing underneath. There is no second entry point for this file and there
 * does not need to be: what it states is true of the portal's parts, not of any deployment of
 * them, and the deployed version of the same promise is proved separately by e2e/features against
 * the live stack.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("account-closure.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.jrobertgardzinski.portal.closure")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
class AccountClosureSpecsTest {
}
