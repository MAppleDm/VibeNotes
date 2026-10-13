package io.github.mappledm.vibenotes.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.mappledm.vibenotes.R
import io.github.mappledm.vibenotes.data.AppFont
import io.github.mappledm.vibenotes.data.CustomFontStore

/**
 * Golos Text: made for Russian interfaces, calm and very legible at small sizes (SIL OFL,
 * see licenses/GolosText-OFL.txt). Static instances of the variable font, 400–700.
 */
val GolosText = FontFamily(
    Font(R.font.golos_text_regular, FontWeight.Normal),
    Font(R.font.golos_text_medium, FontWeight.Medium),
    Font(R.font.golos_text_semibold, FontWeight.SemiBold),
    Font(R.font.golos_text_bold, FontWeight.Bold),
)

/** A missing or unreadable custom font falls back to the default, never to a crash. */
fun appFontFamily(font: AppFont, custom: CustomFontStore): FontFamily = when (font) {
    AppFont.GOLOS -> GolosText
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.CUSTOM -> custom.typeface()?.let { FontFamily(it) } ?: GolosText
}
