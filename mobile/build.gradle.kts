plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

val checkMobileI18n by tasks.registering {
    group = "verification"
    description = "Rejects user-visible string literals outside shared localization resources."
    val resourceCatalogs = mapOf(
        "en-US" to file("shared/src/commonMain/composeResources/values/strings.xml"),
        "zh-CN" to file("shared/src/commonMain/composeResources/values-zh-rCN/strings.xml"),
        "zh-SG" to file("shared/src/commonMain/composeResources/values-zh-rSG/strings.xml"),
    )
    val productionSources = files(
        fileTree("shared/src/commonMain/kotlin") { include("**/*.kt") },
        fileTree("shared/src/androidMain/kotlin") { include("**/*.kt") },
        fileTree("androidApp/src/main/kotlin") { include("**/*.kt") },
    )
    inputs.files(productionSources, resourceCatalogs.values)
    doLast {
        val hanLiteral = Regex("\"[^\"]*[\\u4E00-\\u9FFF][^\"]*\"")
        val visibleLiteral = Regex("(?:Text\\s*\\(|contentDescription\\s*=|setContentTitle\\s*\\(|setContentText\\s*\\()\\s*\"[^\"]*[A-Za-z][^\"]*\"")
        val violations = productionSources.files.flatMap { file ->
            file.readLines().flatMapIndexed { index, line ->
                (hanLiteral.findAll(line) + visibleLiteral.findAll(line))
                    .map { match -> "${file.relativeTo(rootDir)}:${index + 1}: ${match.value}" }
                    .toList()
            }
        }.toMutableList()

        val resourcePattern = Regex("""<string\s+name="([^"]+)">(.*?)</string>""")
        val placeholderPattern = Regex("""\{(\d+)}""")
        val forbiddenPrefix = Regex("""^(?:en|zh|zh_hans|zh_hant)_""", RegexOption.IGNORE_CASE)
        val catalogs = resourceCatalogs.mapValues { (locale, file) ->
            val entries = resourcePattern.findAll(file.readText()).map { match ->
                match.groupValues[1] to match.groupValues[2]
            }.toList()
            val duplicates = entries.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
            duplicates.forEach { violations += "$locale: duplicate resource key '$it'" }
            entries.toMap()
        }
        val defaultCatalog = catalogs.getValue("en-US")
        defaultCatalog.forEach { (key, value) ->
            if (forbiddenPrefix.containsMatchIn(key)) violations += "en-US: language-prefixed resource key '$key'"
            if (hanLiteral.containsMatchIn("\"$value\"")) violations += "en-US: default resource '$key' contains Han text"
            if (value.isBlank()) violations += "en-US: default resource '$key' is blank"
        }
        catalogs.filterKeys { it != "en-US" }.forEach { (locale, catalog) ->
            val missing = defaultCatalog.keys - catalog.keys
            val extra = catalog.keys - defaultCatalog.keys
            if (missing.isNotEmpty()) violations += "$locale: missing keys ${missing.sorted()}"
            if (extra.isNotEmpty()) violations += "$locale: unexpected keys ${extra.sorted()}"
            defaultCatalog.keys.intersect(catalog.keys).forEach { key ->
                val expected = placeholderPattern.findAll(defaultCatalog.getValue(key)).map { it.groupValues[1] }.toList()
                val actual = placeholderPattern.findAll(catalog.getValue(key)).map { it.groupValues[1] }.toList()
                if (expected != actual) violations += "$locale: placeholder mismatch for '$key': expected $expected, actual $actual"
                if (catalog.getValue(key).isBlank()) violations += "$locale: resource '$key' is blank"
            }
        }

        val distinctViolations = violations.distinct()
        if (distinctViolations.isNotEmpty()) {
            throw GradleException("Mobile localization check failed:\n${distinctViolations.joinToString("\n")}")
        }
    }
}

subprojects {
    tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
        dependsOn(checkMobileI18n)
    }
}
