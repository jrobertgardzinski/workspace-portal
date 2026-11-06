package com.jrobertgardzinski.memes.infrastructure;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Runs the meme specs through Cucumber against the Spring app, reporting to Allure.
 */
@Suite
@IncludeEngines("cucumber")
// one Gherkin file per use case in the top-level specs/ dir (build-helper puts them on the
// classpath root — microservice-security's convention), selected explicitly, file by file
// (selecting single files, unlike selecting a package, is not deprecated)
@SelectClasspathResource("upload-meme.feature")
@SelectClasspathResource("vote-meme.feature")
@SelectClasspathResource("tag-meme.feature")
@SelectClasspathResource("delete-meme.feature")
@SelectClasspathResource("flag-meme.feature")
@SelectClasspathResource("admin-purge-policy.feature")
@SelectClasspathResource("account-erasure.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.jrobertgardzinski.memes.infrastructure.cucumber")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
public class RunCucumberTest {
}
