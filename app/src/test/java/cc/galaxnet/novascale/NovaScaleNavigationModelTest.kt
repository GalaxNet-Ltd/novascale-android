/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import cc.galaxnet.novascale.core.TailnetPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaScaleNavigationModelTest {
    @Test
    fun eachSectionRestoresItsOwnDestination() {
        val model = NovaScaleNavigationModel()
        val peer = testPeer("host")
        model.open(AppDestination.Files(peer))

        model.selectSection(AppSection.SETTINGS)
        assertEquals(AppSection.SETTINGS, model.uiState.value.section)
        assertNull(model.uiState.value.destination)

        model.open(AppDestination.Source)
        model.selectSection(AppSection.HOME)

        assertEquals(AppSection.HOME, model.uiState.value.section)
        assertEquals(AppDestination.Files(peer), model.uiState.value.destination)

        model.selectSection(AppSection.SETTINGS)
        assertEquals(AppSection.SETTINGS, model.uiState.value.section)
        assertEquals(AppDestination.Source, model.uiState.value.destination)
    }

    @Test
    fun openingAWindowCanSetItsOwningSectionAtomically() {
        val model = NovaScaleNavigationModel()

        model.open(AppSection.SETTINGS, AppDestination.Source)

        assertEquals(AppSection.SETTINGS, model.uiState.value.section)
        assertEquals(AppDestination.Source, model.uiState.value.destination)
        assertNull(model.uiState.value.destinationFor(AppSection.HOME))
    }

    @Test
    fun openingWithinOneSectionDoesNotChangeTheOtherStack() {
        val model = NovaScaleNavigationModel()
        val peer = testPeer("host")
        model.open(AppDestination.Host(peer))
        model.open(AppSection.SETTINGS, AppDestination.TerminalSettings)

        model.open(AppDestination.Source)

        assertEquals(AppDestination.Host(peer), model.uiState.value.destinationFor(AppSection.HOME))
        assertEquals(AppDestination.Source, model.uiState.value.destinationFor(AppSection.SETTINGS))
    }

    @Test
    fun resetClearsBothStacksAfterGlobalLogin() {
        val model = NovaScaleNavigationModel()
        val peer = testPeer("host")
        model.open(AppDestination.Host(peer))
        model.open(AppSection.SETTINGS, AppDestination.Source)

        model.resetToHome()

        assertEquals(NovaScaleNavigationState(), model.uiState.value)
    }

    @Test fun changingProfileClearsPeerDestinationsInEverySection() {
        val model = NovaScaleNavigationModel()
        model.bindProfile("a")
        model.open(AppDestination.Files(testPeer("same-id")))
        model.open(AppSection.SETTINGS, AppDestination.Tailnets)
        model.bindProfile("b")
        assertNull(model.uiState.value.homeDestination)
        assertEquals(AppDestination.Tailnets, model.uiState.value.settingsDestination)
    }

    @Test fun editingLoginServerKeepsSetupOpenWhileOldHostDestinationsAreCleared() {
        val model = NovaScaleNavigationModel()
        model.bindProfile("original")
        model.open(AppDestination.Host(testPeer("old-network")))
        model.open(AppSection.SETTINGS, AppDestination.Login)
        model.invalidateTailnetDestinations()
        model.bindProfile("replacement")
        assertEquals(AppDestination.Login, model.uiState.value.destination)
        assertNull(model.uiState.value.homeDestination)
    }

    private fun testPeer(id: String) = TailnetPeer(
        stableNodeId = id,
        displayName = id,
        dnsName = "$id.example",
        addresses = listOf("100.64.0.1"),
        online = true,
        sshAvailable = true,
    )
}
