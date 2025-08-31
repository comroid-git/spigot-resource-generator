package org.comroid.codegen.spigot

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

class SpigotResourceGeneratorPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (!project.plugins.hasPlugin('java'))
            throw new IllegalStateException("A java module is required")

        project.configurations { c ->
            c.create 'generated'
            c.implementation.extendsFrom c.generated
        }

        project.dependencies { d ->
            d.generated 'org.comroid:japi:+'
            d.generated 'org.projectlombok:lombok:+'
            d.annotationProcessor 'org.projectlombok:lombok:+'
        }

        def sources = project.extensions.getByType(SourceSetContainer)
        sources.maybeCreate('generated').java.srcDirs "${project.layout.buildDirectory.get().asFile.absolutePath}/generated/sources/r"

        def tasks = project.tasks
        var task = tasks.register("generateSpigotResourceClasses", GenerateSpigotResourceClassesTask).get().configure {
            it.group = 'build'
            it.description = 'Generates Resource Accessors for Spigot plugin.yml and other resources'
        }
        tasks.named('compileJava').get().dependsOn task
    }
}