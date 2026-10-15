package com.noneplayer.kernel.ui.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 设备姿态输入的**播种**回归（E132）。
 *
 * 飞行层的 `rotationZ` 从 `-turn` 起、由弹簧回正，而 `turn` 取自
 * `pictureTurnDegrees(previousRotation, displayRotation)`。进程起来那一次上报如果
 * 被当成"从 0° 转到了 180°"，第一次转屏就会拿到一个莫明其妙的自转——所以 seeding
 * 必须单独钉住，而不是只看 `previousRotation` 顺带对一下。
 */
class NpDevicePoseTest {

    @Before
    fun setUp() {
        NpDevicePose.resetForTests()
    }

    @After
    fun tearDown() {
        NpDevicePose.resetForTests()
    }

    /** 第一次上报只播种：角度落在当前值上，turn 为 0。 */
    @Test
    fun `first report seeds without inventing a turn`() {
        NpDevicePose.noteDisplayRotation(2)
        assertEquals(2, NpDevicePose.displayRotation)
        assertEquals("播种不得把 previous 留在 0", 2, NpDevicePose.previousRotation)
        assertEquals(0, pictureTurnDegrees(NpDevicePose.previousRotation, NpDevicePose.displayRotation))
    }

    /** 同一个角度反复上报（两条路径各报一次是常态）不得改动 previous。 */
    @Test
    fun `repeat of the same rotation keeps the pair stable`() {
        NpDevicePose.noteDisplayRotation(2)
        NpDevicePose.noteDisplayRotation(2)
        assertEquals(2, NpDevicePose.previousRotation)
        assertEquals(2, NpDevicePose.displayRotation)
    }

    /** 真转了一次：previous 记下旧值，turn 走顺时针口径。 */
    @Test
    fun `real rotation moves previous and yields clockwise turn`() {
        NpDevicePose.noteDisplayRotation(0)
        NpDevicePose.noteDisplayRotation(1)
        assertEquals(0, NpDevicePose.previousRotation)
        assertEquals(1, NpDevicePose.displayRotation)
        assertEquals(90, pictureTurnDegrees(NpDevicePose.previousRotation, NpDevicePose.displayRotation))
    }

    /** 越界读数（ORIENTATION_UNKNOWN = -1）必须退回 null，不能当成 0° 直立。 */
    @Test
    fun `tilt ignores readings outside the range`() {
        NpDevicePose.noteTilt(45)
        assertEquals(45, NpDevicePose.tiltDegrees ?: -1)
        NpDevicePose.noteTilt(-1)
        assertNull(NpDevicePose.tiltDegrees)
        NpDevicePose.noteTilt(360)
        assertNull(NpDevicePose.tiltDegrees)
    }

    /** 铰链开合不是转腕：separating 的采样要能被读出来，并在扁平后清空。 */
    @Test
    fun `hinge sample round trips`() {
        val separating = NpHingeSample(horizontal = true, separating = true)
        NpDevicePose.noteHinge(separating)
        assertEquals(separating, NpDevicePose.hinge)
        NpDevicePose.noteHinge(null)
        assertNull(NpDevicePose.hinge)
        // 分离态下残差不该进 rotationZ（这是 pictureRotationZ 的既有判据，这里串一遍口径）
        NpDevicePose.noteHinge(separating)
        NpDevicePose.noteTilt(20)
        assertEquals(
            -90f,
            pictureRotationZ(-90f, NpDevicePose.tiltDegrees, NpDevicePose.displayRotation, NpDevicePose.hinge),
            0.01f,
        )
    }
}
