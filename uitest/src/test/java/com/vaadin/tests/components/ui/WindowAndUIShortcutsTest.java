/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.tests.components.ui;

import org.junit.Assert;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;

import com.vaadin.testbench.elements.ButtonElement;
import com.vaadin.testbench.elements.TextFieldElement;
import com.vaadin.testbench.elements.WindowElement;
import com.vaadin.tests.tb3.SingleBrowserTest;

public class WindowAndUIShortcutsTest extends SingleBrowserTest {

    @Test
    public void windowShortcutShouldNotReachUI() {
        openTestURL();
        $(ButtonElement.class).caption("Show page").first().click();
        $(ButtonElement.class).caption("Open dialog window").first().click();

        WindowElement window = $(WindowElement.class).first();
        // for PhantomJS to have the focus in the right place
        window.click();
        window.$(TextFieldElement.class).first().sendKeys(Keys.ESCAPE);

        // Window should have been closed
        Assert.assertTrue($(WindowElement.class).all().isEmpty());
        // "Close page" should not have been clicked
        Assert.assertTrue(
                $(ButtonElement.class).caption("Close page").exists());
    }

    @Test
    public void modalCurtainShouldNotTriggerShortcuts() {
        openTestURL();
        $(ButtonElement.class).caption("Show page").first().click();
        $(ButtonElement.class).caption("Open dialog window").first().click();

        WebElement curtain = findElement(
                By.className("v-window-modalitycurtain"));
        curtain.sendKeys(Keys.ESCAPE);
        // "Close page" should not have been clicked
        Assert.assertTrue(
                $(ButtonElement.class).caption("Close page").exists());

    }
}
