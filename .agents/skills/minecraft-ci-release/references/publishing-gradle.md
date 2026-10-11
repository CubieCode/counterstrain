# Gradle Publishing Reference (NeoForge 1.21.1, ModDevGradle)

The preferred route is the `Kira-NT/mc-publish` action in `SKILL.md`. Use these Gradle
plugins only if the project chooses Gradle-side publishing. Examples use Groovy DSL
(`mod/build.gradle`, as in the NeoForge MDK); translate if the project uses Kotlin DSL.
Plugin versions verified on the Gradle Plugin Portal on 2026-10-11.

## Tag/version check and changelog

```groovy
def changelogFor(String v) {
    def text = rootProject.file('CHANGELOG.md').text
    def heading = "## [${v}]"
    int start = text.indexOf(heading)
    if (start < 0) throw new GradleException("CHANGELOG.md is missing heading: ${heading}")
    def body = text.substring(start + heading.length()).split(/\n## \[/)[0].trim()
    if (!body) throw new GradleException("CHANGELOG.md section is empty: ${heading}")
    return body
}

tasks.register('verifyReleaseVersion') {
    group = 'verification'
    def modVersion = project.mod_version
    def tagVersion = providers.gradleProperty('releaseModVersion')
    doLast {
        def v = tagVersion.getOrElse('')
        if (v != modVersion) {
            throw new GradleException("Tag version '${v}' does not match mod_version=${modVersion}")
        }
        changelogFor(v)
    }
}
```

Invoke with `./gradlew verifyReleaseVersion -PreleaseModVersion=${GITHUB_REF_NAME#mod-v}`.
Look up the changelog by `mod_version`, never by `project.version` (which may carry `+1.21.1`).

## Modrinth: Minotaur

```groovy
plugins {
    id 'com.modrinth.minotaur' version '2.10.0'
}

modrinth {
    token = providers.environmentVariable('MODRINTH_TOKEN').getOrElse('')
    projectId = providers.gradleProperty('modrinth_project_id')
    versionNumber = project.mod_version
    versionType = 'release'
    uploadFile = jar            // ModDevGradle: the plain `jar` task is the distributable
    gameVersions = ['1.21.1']
    loaders = ['neoforge']      // not auto-detected for ModDevGradle
    changelog = providers.provider { changelogFor(project.mod_version) }
}
tasks.named('modrinth') { dependsOn 'verifyReleaseVersion' }
```

## CurseForge: CurseForgeGradle

```groovy
plugins {
    id 'net.darkhax.curseforgegradle' version '1.3.33'
}

tasks.register('publishCurseForge', net.darkhax.curseforgegradle.TaskPublishCurseForge) {
    dependsOn 'verifyReleaseVersion'
    apiToken = providers.environmentVariable('CURSEFORGE_TOKEN').getOrElse('')
    def mainFile = upload(providers.gradleProperty('curseforge_project_id').get(), jar)
    mainFile.releaseType = 'release'
    mainFile.changelogType = 'markdown'
    mainFile.changelog = changelogFor(project.mod_version)
    // Loader (NeoForge via net.neoforged.moddev), game and Java versions are auto-detected;
    // add explicitly only if detection is disabled:
    // mainFile.addModLoader('NeoForge'); mainFile.addGameVersion('1.21.1'); mainFile.addJavaVersion('Java 21')
    mainFile.addEnvironment('Client', 'Server')
}
```

Set `addEnvironment` to what the JAR actually supports. Pass tokens only as environment
variables from GitHub secrets; never put them in `gradle.properties`.
