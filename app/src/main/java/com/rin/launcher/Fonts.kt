package com.rin.launcher

import android.content.Context
import android.graphics.Typeface

class RinFont(val name: String, val file: String)

object Fonts {
    val all: List<RinFont> = listOf(
        RinFont("Inter", "inter_regular.otf"),
        RinFont("Inter Display Light", "inter_display_light.otf"),
        RinFont("Inter Bold", "inter_bold.otf"),
        RinFont("Inter Display Black", "inter_display_black.otf"),
        RinFont("Roboto", "roboto.ttf"),
        RinFont("Open Sans", "open_sans.ttf"),
        RinFont("Source Sans 3", "source_sans.ttf"),
        RinFont("Fira Sans", "fira_regular.ttf"),
        RinFont("Fira Sans Light", "fira_light.ttf"),
        RinFont("Fira Sans Medium", "fira_medium.ttf"),
        RinFont("Fira Sans Bold", "fira_bold.ttf"),
        RinFont("Fira Sans Black", "fira_black.ttf"),
        RinFont("Fira Sans Italic", "fira_italic.ttf"),
        RinFont("Carlito", "carlito_regular.ttf"),
        RinFont("Carlito Bold", "carlito_bold.ttf"),
        RinFont("Comic Relief", "comic_regular.ttf"),
        RinFont("Comic Relief Bold", "comic_bold.ttf"),
        RinFont("Anonymous Pro", "anon_regular.ttf"),
        RinFont("Anonymous Pro Bold", "anon_bold.ttf"),
        RinFont("DejaVu Sans", "dejavu_sans.ttf"),
        RinFont("DejaVu Sans Condensed", "dejavu_sans_cond.ttf"),
        RinFont("DejaVu Sans Bold", "dejavu_sans_bold.ttf"),
        RinFont("DejaVu Sans Mono", "dejavu_mono.ttf"),
        RinFont("DejaVu Serif", "dejavu_serif.ttf"),
        RinFont("DejaVu Serif Condensed Bold", "dejavu_serif_cond_bold.ttf"),
        RinFont("Liberation Sans", "lib_sans.ttf"),
        RinFont("Liberation Sans Narrow", "lib_narrow.ttf"),
        RinFont("Liberation Sans Bold", "lib_sans_bold.ttf"),
        RinFont("Liberation Serif", "lib_serif.ttf"),
        RinFont("Liberation Serif Italic", "lib_serif_italic.ttf"),
        RinFont("Liberation Mono", "lib_mono.ttf"),
        RinFont("Lora", "lora.ttf"),
        RinFont("Lora Italic", "lora_italic.ttf"),
        RinFont("STIX General", "stix_regular.ttf"),
        RinFont("STIX General Bold Italic", "stix_bold_italic.ttf"),
        RinFont("FreeSans", "free_sans.ttf"),
        RinFont("FreeMono", "free_mono.ttf"),
        RinFont("Inter Thin", "inter_thin.otf"),
        RinFont("Fira Sans ExtraLight", "fira_extralight.ttf"),
        RinFont("DejaVu Sans ExtraLight", "dejavu_extralight.ttf")
    )

    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun load(ctx: Context, f: RinFont): Typeface {
        val hit = cache[f.file]
        if (hit != null) return hit
        val tf: Typeface = try {
            Typeface.createFromAsset(ctx.assets, "fonts/" + f.file)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cache[f.file] = tf
        return tf
    }
}
