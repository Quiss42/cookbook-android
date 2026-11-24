#!/usr/bin/env python3
"""Ports matching strings from the Flutter app's i18n JSON files to Android resources.

Only keys whose meaning matches an Android string are taken; everything else
falls back to English. German is maintained by hand and skipped here.
Usage: port_translations.py <flutter i18n dir> <android res dir>
"""
import json
import os
import sys
from xml.sax.saxutils import escape

MAPPING = {
    "logout": "app_bar.logout",
    "discard": "alert.discard",
    "clear": "alert.clear",
    "field_required": "form.validators.required",
    "login_server": "login.server_url.field",
    "login_username": "login.username.field",
    "login_button": "login.button",
    "login_advanced": "login.settings.title",
    "category_all": "categories.all_categories",
    "category_uncategorized": "categories.uncategorized",
    "settings": "categories.drawer.settings",
    "import_title": "recipe_import.title",
    "import_button": "recipe_import.button",
    "import_url": "recipe_import.field",
    "field_name": "recipe.fields.name",
    "field_description": "recipe.fields.description",
    "field_keywords": "recipe.fields.keywords",
    "field_category": "recipe.fields.category",
    "field_servings": "recipe.fields.servings.else",
    "field_source": "recipe.fields.source",
    "source": "recipe.fields.source_button",
    "field_prep_time": "recipe.fields.time.prep",
    "field_cook_time": "recipe.fields.time.cook",
    "field_total_time": "recipe.fields.time.total",
    "field_hours": "recipe.fields.time.hours",
    "field_minutes": "recipe.fields.time.minutes",
    "nutrition_calories": "recipe.fields.nutrition.items.calories",
    "nutrition_carbohydrates": "recipe.fields.nutrition.items.carbohydrateContent",
    "nutrition_cholesterol": "recipe.fields.nutrition.items.cholesterolContent",
    "nutrition_fat": "recipe.fields.nutrition.items.fatContent",
    "nutrition_fiber": "recipe.fields.nutrition.items.fiberContent",
    "nutrition_protein": "recipe.fields.nutrition.items.proteinContent",
    "nutrition_saturated_fat": "recipe.fields.nutrition.items.saturatedFatContent",
    "nutrition_serving_size": "recipe.fields.nutrition.items.servingSize",
    "nutrition_sodium": "recipe.fields.nutrition.items.sodiumContent",
    "nutrition_sugar": "recipe.fields.nutrition.items.sugarContent",
    "nutrition_trans_fat": "recipe.fields.nutrition.items.transFatContent",
    "nutrition_unsaturated_fat": "recipe.fields.nutrition.items.unsaturatedFatContent",
    "edit_section_general": "recipe.fields.general",
    "edit_section_times": "recipe.fields.times",
    "tools": "recipe.fields.tools",
    "ingredients": "recipe.fields.ingredients",
    "instructions": "recipe.fields.instructions",
    "edit_title": "recipe_edit.title",
    "save": "recipe_edit.button",
    "edit_new_title": "recipe_create.title",
    "delete_title": "recipe_edit.delete.title",
    "search_hint": "search.title",
    "search_prompt": "search.description",
    "search_no_results": "search.nothing_found",
    "timers": "timer.title",
    "timer_started": "timer.started",
    "timer_start": "timer.button.start",
    "timer_cancel": "timer.button.cancel",
    "timer_done_title": "timer.done",
    "timers_empty": "timer.empty_list",
    "settings_language": "settings.language.title",
    "settings_keep_screen_on": "settings.stay_awake.title",
    "settings_keep_screen_on_hint": "settings.stay_awake.subtitle",
    "theme_system": "settings.dark_mode.system",
}

# Flutter file name -> Android resource qualifier
LOCALES = {
    "bg_BG": "bg", "cs_CZ": "cs", "es": "es", "eu": "eu", "fi_FI": "fi",
    "fr": "fr", "gl": "gl", "he": "iw", "hr": "hr", "hu_HU": "hu", "is": "is",
    "it": "it", "nl": "nl", "pl": "pl", "pt_BR": "pt-rBR", "ru": "ru",
    "sc": "sc", "sk_SK": "sk", "sl": "sl", "tr": "tr", "vi": "vi",
    "zh_CN": "zh-rCN", "zh_HK": "zh-rHK",
}

CAPITALIZE = {"field_required", "timer_start", "timer_cancel"}


def lookup(data, dotted):
    for part in dotted.split("."):
        if not isinstance(data, dict) or part not in data:
            return None
        data = data[part]
    return data if isinstance(data, str) else None


def android_escape(text):
    text = escape(text.strip())
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text


def main(src, res):
    english = json.load(open(os.path.join(src, "en.json"), encoding="utf-8"))
    for name, qualifier in sorted(LOCALES.items()):
        path = os.path.join(src, f"{name}.json")
        if not os.path.exists(path):
            continue
        data = json.load(open(path, encoding="utf-8"))
        lines = []
        for key, source in MAPPING.items():
            value = lookup(data, source)
            if not value or not value.strip() or "{" in value or value == lookup(english, source):
                continue
            value = value.strip()
            if key in CAPITALIZE:
                value = value[:1].upper() + value[1:]
            attrs = ' formatted="false"' if "%" in value else ""
            lines.append(f'    <string name="{key}"{attrs}>{android_escape(value)}</string>')
        if not lines:
            continue
        out_dir = os.path.join(res, f"values-{qualifier}")
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "strings.xml"), "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write(f"<!-- Generated by tools/port_translations.py from the Flutter app ({name}.json). -->\n")
            f.write("<resources>\n" + "\n".join(lines) + "\n</resources>\n")
        print(f"{qualifier:7} {len(lines):3} strings")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
