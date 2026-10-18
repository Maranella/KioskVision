package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.ElementRole
import com.maranella.kioskvision.assist.MoneyParser
import com.maranella.kioskvision.assist.OptionPricing
import com.maranella.kioskvision.assist.PricedOption
import com.maranella.kioskvision.assist.SceneElement
import com.maranella.kioskvision.assist.TextBox
import com.maranella.kioskvision.assist.TextNormalizer
import com.maranella.kioskvision.ocr.KioskOcrEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does a price actually reach the right drink, through real OCR, on this device?
 *
 * [OptionPricingTest] pins the association rules against a hand-written layout. This runs the
 * same rules over text that **Tesseract really produced** from `coffee_machine_screen.png`,
 * whose ground truth is published in that asset's README, at two sizes.
 *
 * ### What this is and is not
 *
 * The image is a flat, evenly lit render displayed from a file, not a photograph of a machine:
 * no glare, no bezel, no optics, no real font. It can show that the pipeline associates
 * correctly when recognition works, and it can show where recognition stops working with size.
 * It cannot estimate field accuracy.
 *
 * ### The assertion that matters
 *
 * Coverage — how many drinks get a price at all — is *reported*, not asserted, because it
 * depends on recognition quality this test is measuring rather than fixing. What is asserted
 * is that **no option is given an amount that is not its own**. A missing price costs the user
 * an answer; a wrong one is the fabrication the safety invariants forbid.
 */
@RunWith(AndroidJUnit4::class)
class PriceAssociationTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** From `assets/ocr_test/README.md`: what the target says, and what each drink costs. */
    private val groundTruth = mapOf(
        "ესპრესო" to 350,
        "ამერიკანო" to 400,
        "კაპუჩინო" to 500,
        "ლატე" to 500,
        "ჯამი" to 850,
    )

    @Test
    fun a_price_reaches_its_own_drink_and_never_another() {
        val source = appContext.assets.open("ocr_test/coffee_machine_screen.png").use {
            BitmapFactory.decodeStream(it)
        }
        val engine = KioskOcrEngine.create(appContext)
        var anyAssociated = false
        try {
            // 1920 px is the file itself — the best case the target can offer. 1280 px is what
            // a 720-wide analysis frame magnified 2x gives when the screen fills the width,
            // which is the realistic case the arithmetic in the README works out.
            for (width in listOf(1920, 1280)) {
                val frame = if (width == source.width) source else source.scale(width, width * source.height / source.width)
                val started = SystemClock.elapsedRealtime()
                val blocks = engine.recognize(frame, null, 1f)
                val elapsed = SystemClock.elapsedRealtime() - started

                val elements = blocks.map { it.asElement(frame) }
                val priced = OptionPricing.pair(elements)
                val amounts = elements.count { MoneyParser.parse(it.label) != null }

                Log.i(
                    TAG,
                    "width=$width ocr=${elapsed}ms blocks=${blocks.size} amounts=$amounts " +
                        "priced=${priced.size} -> " + priced.joinToString { "${it.label}=${it.price.raw}" },
                )

                // Every line, so a failure to price can be read as "not recognised" or
                // "recognised and refused" without a second run.
                for (block in blocks) {
                    Log.i(TAG, "width=$width line '${block.text.trim()}' conf=${block.confidence}")
                }

                for (option in priced) {
                    val expected = expectedFor(option)
                    if (expected == null) {
                        // An association to a line that is not one of the known options. Not a
                        // failure in itself — the target has a service fee that labels itself —
                        // but it must not be one of the drinks under a misread name.
                        Log.i(TAG, "width=$width unlisted option '${option.label}' = ${option.price.raw}")
                        continue
                    }
                    anyAssociated = true
                    assertTrue(
                        "width=$width: '${option.label}' was given ${option.price.tetri} " +
                            "tetri, which belongs to another option (expected $expected)",
                        option.price.tetri == expected,
                    )
                }
            }
        } finally {
            engine.close()
            source.recycle()
        }

        // If nothing associated at either size, the test proved nothing about association and
        // should say so rather than pass quietly.
        assertTrue(
            "no option was priced at either size; recognition, not association, is what failed",
            anyAssociated,
        )
    }

    private fun expectedFor(option: PricedOption): Int? {
        val normalized = TextNormalizer.normalize(option.label)
        return groundTruth.entries
            .firstOrNull { (name, _) -> normalized.contains(TextNormalizer.normalize(name)) }
            ?.value
    }

    private fun DetectedTextBlock.asElement(frame: Bitmap) = SceneElement(
        label = text.trim(),
        screenBounds = TextBox(
            left = boundingBox.left / frame.width,
            top = boundingBox.top / frame.height,
            right = boundingBox.right / frame.width,
            bottom = boundingBox.bottom / frame.height,
        ),
        // The target is displayed flat and square-on, so frame coordinates are screen
        // coordinates here. A photograph taken at an angle would not be, which is exactly
        // why the production path refuses to guide without a rectified screen.
        boundsAreScreenRelative = true,
        confidence = confidence,
        timesSeen = 1,
        role = ElementRole.of(text),
    )

    private companion object {
        const val TAG = "PriceAssociation"
    }
}
