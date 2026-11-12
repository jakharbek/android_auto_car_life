package com.jakharbek.monzaauto.setup

import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.Diag.AaStage
import com.jakharbek.monzaauto.Diag.CarStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    private fun snap(
        car: CarStage = CarStage.NONE, aa: AaStage = AaStage.NONE, refused: Boolean = false, timedOut: Boolean = false,
        tls: Boolean = false, auth: Boolean = false, features: Map<String, Int>? = null,
    ) = Diag.Snapshot(
        car, "кабель", null, false, "2.0", "SAIC MyLink", "1280×720", features, auth, 42,
        aa, "x", refused, timedOut, tls, "1.7", 100, true, true,
    )

    @Test
    fun androidAutoMissingIsFailWithInstallFix() {
        val c = Rules.androidAuto(null, false)
        assertEquals(Level.FAIL, c.level)
        assertEquals(Fix.INSTALL_AA, c.fix)
        assertEquals(Level.OK, Rules.androidAuto("16.2", true).level)
        assertEquals(Level.FAIL, Rules.androidAuto("16.2", false).level)
    }

    @Test
    fun certificateStates() {
        assertEquals(Level.FAIL, Rules.certificate(false, false, 0, 0, null, now).level)
        assertEquals(Fix.LOAD_CERT, Rules.certificate(true, false, 0, now + 900 * day, null, now).fix)
        assertEquals(Level.FAIL, Rules.certificate(true, true, 0, now - day, null, now).level)
        assertEquals(Level.FAIL, Rules.certificate(true, true, now + day, now + 900 * day, null, now).level)
        assertEquals(Level.WARN, Rules.certificate(true, true, 0, now + 10 * day, null, now).level)
        val ok = Rules.certificate(true, true, 0, now + 900 * day, "Acme", now)
        assertEquals(Level.OK, ok.level)
        assertTrue(ok.detail.contains("Acme"))
    }

    @Test
    fun serverProbe() {
        assertEquals(Level.FAIL, Rules.server(ServerProbe.NOT_RUNNING, snap()).level)
        assertEquals(Fix.OPEN_AA_SETTINGS, Rules.server(ServerProbe.NOT_RUNNING, snap()).fix)
        assertEquals(Level.OK, Rules.server(ServerProbe.RUNNING, snap()).level)
        assertEquals(Level.OK, Rules.server(ServerProbe.IN_USE_BY_APP, snap()).level)
        // running, but the last session timed out: the one-shot server has to be restarted
        assertEquals(Level.WARN, Rules.server(ServerProbe.RUNNING, snap(aa = AaStage.CLOSED, timedOut = true)).level)
        // any finished session used the server up
        assertEquals(Level.WARN, Rules.server(ServerProbe.RUNNING, snap(aa = AaStage.CLOSED)).level)
        // a refused connection means it was not running then; now it is, and fresh
        assertEquals(Level.OK, Rules.server(ServerProbe.RUNNING, snap(aa = AaStage.CLOSED, refused = true)).level)
    }

    @Test
    fun batteryAndPermissions() {
        assertEquals(Level.FAIL, Rules.battery(true, true).level)
        assertEquals(Fix.BATTERY, Rules.battery(false, false).fix)
        assertEquals(Level.OK, Rules.battery(true, false).level)
        assertEquals(Fix.ASK_MIC, Rules.microphone(false).fix)
        assertEquals(Fix.ASK_NOTIFICATIONS, Rules.notifications(false, 34).fix)
    }

    @Test
    fun carStages() {
        assertEquals(Level.INFO, Rules.car(snap()).level)
        assertEquals(Level.WARN, Rules.car(snap(car = CarStage.USB_OPEN)).level)
        assertEquals(Level.OK, Rules.car(snap(car = CarStage.STREAMING)).level)
        assertTrue(Rules.car(snap(car = CarStage.STREAMING)).detail.contains("SAIC MyLink"))
        assertEquals(Level.FAIL, Rules.car(snap(car = CarStage.TALKING, auth = true)).level)
        assertEquals(Fix.ATTACH_CAR, Rules.car(snap(car = CarStage.CLOSED)).fix)
    }

    @Test
    fun androidAutoLinkFailures() {
        assertEquals(Fix.OPEN_AA_SETTINGS, Rules.androidAutoLink(snap(aa = AaStage.CLOSED, refused = true), true).fix)
        assertEquals(Fix.LOAD_CERT, Rules.androidAutoLink(snap(aa = AaStage.CLOSED, tls = true), true).fix)
        assertEquals(Level.FAIL, Rules.androidAutoLink(snap(aa = AaStage.CLOSED, timedOut = true), true).level)
        assertEquals(Fix.CONNECT_AA, Rules.androidAutoLink(snap(aa = AaStage.CLOSED), true).fix)
        assertEquals(Level.OK, Rules.androidAutoLink(snap(aa = AaStage.VIDEO), true).level)
    }

    @Test
    fun linkProblemShownOnceWhenServerIsDown() {
        val server = Rules.server(ServerProbe.NOT_RUNNING, snap())
        val link = Rules.androidAutoLink(snap(aa = AaStage.CLOSED, refused = true), true)
        val d = Rules.dedupe(link, server)
        assertEquals(Level.INFO, d.level)
        assertEquals(Fix.NONE, d.fix)
        // when the server is up again, a refusal is still reported in full
        assertEquals(Level.FAIL, Rules.dedupe(link, Rules.server(ServerProbe.RUNNING, snap())).level)
    }

    @Test
    fun defaultAppForHeadUnit() {
        val ok = Rules.defaultApp(emptyList(), attached = false, havePermission = false)
        assertEquals(Level.OK, ok.level)
        assertTrue(ok.todo!!.contains("Всегда"))
        val other = AccessoryApp("com.example.other", "Другое")
        val warn = Rules.defaultApp(listOf(other), attached = false, havePermission = false)
        assertEquals(Level.WARN, warn.level)
        assertEquals(Fix.OPEN_APP_DEFAULTS, warn.fix)
        assertEquals("com.example.other", warn.fixArg)
        assertTrue(warn.detail.contains("«Другое»"))
        // plugged in, but the other app got it
        assertEquals(Level.FAIL, Rules.defaultApp(listOf(other), attached = true, havePermission = false).level)
        assertEquals(Level.WARN, Rules.defaultApp(listOf(other), attached = true, havePermission = true).level)
    }

    @Test
    fun audioAdvice() {
        assertEquals(Level.WARN, Rules.audio("USB", 1).level)
        assertEquals(Level.OK, Rules.audio("USB", 0).level)
        assertEquals(Level.INFO, Rules.audio("PHONE", null).level)
        assertTrue(Rules.audio("AUTO", 1).todo!!.contains("Bluetooth"))
    }

    @Test
    fun sortingSummaryAndReport() {
        val list = listOf(
            Check("a", "A", Level.OK, "ok"),
            Check("b", "B", Level.FAIL, "bad", "fix it"),
            Check("c", "C", Level.WARN, "hmm"),
        )
        assertEquals(listOf("b", "c", "a"), Rules.sorted(list).map { it.id })
        assertEquals("Нужно исправить: 1, обратить внимание: 1", Rules.summary(list))
        assertEquals("Всё готово", Rules.summary(listOf(list[0])))
        val r = Rules.report(Rules.sorted(list), "H")
        assertTrue(r.startsWith("H\n"))
        assertTrue(r.contains("[X]   B: bad\n      → fix it"))
    }
}
