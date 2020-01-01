import org.apache.batik.gvt.renderer.*
import org.apache.batik.transcoder.*
import org.apache.batik.transcoder.image.*
import org.apache.batik.transcoder.keys.*
import org.apache.commons.io.*
import org.apache.commons.io.output.*
import java.awt.*
import java.awt.image.*
import javax.imageio.*
import java.awt.RenderingHints
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.Image
import java.security.MessageDigest

System.setProperty("java.awt.headless", "true")

// https://docs.gradle.org/5.0/userguide/kotlin_dsl.html
buildscript {
    repositories {
        mavenLocal()
        google()
		jcenter()
		maven { url = uri("http://dl.bintray.com/korlibs/korlibs/") }
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.apache.xmlgraphics:batik-transcoder:1.10")
        classpath("org.apache.xmlgraphics:batik-svgrasterizer:1.10")
		classpath("com.soywiz.korlibs.korim:korim-jvm:1.9.4")
    }
}

fun BufferedImage.resized(width: Int, height: Int): BufferedImage {
    val tmp = this.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val dimg = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g2d = dimg.createGraphics()
    g2d.drawImage(tmp, 0, 0, null)
    g2d.dispose()
    return dimg
}

fun File.writeImage(image: BufferedImage, format: String = "png") = ImageIO.write(image, format, this)
fun File.readImage(): BufferedImage = ImageIO.read(this)

fun rasterizeSVG(input: File, width: Int? = null, height: Int? = null): BufferedImage {
    val inp = input.readBytes().inputStream()
    val out = ByteArrayOutputStream()
    object : PNGTranscoder() {
        override fun createRenderer(): ImageRenderer = StaticRenderer().apply {
            renderingHints = renderingHints.apply {
                add(RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON))
                add(RenderingHints(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY))
                add(RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE))
                add(RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
                add(RenderingHints(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY))
                add(RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON))
                add(RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY))
                add(RenderingHints(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE))
                add(RenderingHints(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON))
                //add(RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF))
                //add(RenderingHints(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED))
                //add(RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE))
                //add(RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
                //add(RenderingHints(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED))
                //add(RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF))
                //add(RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED))
                //add(RenderingHints(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE))
                //add(RenderingHints(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF))
            }
        }
    }.apply {
        if (width != null) addTranscodingHint(PNGTranscoder.KEY_WIDTH, width.toFloat())
        if (height != null) addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height.toFloat())
        transcode(TranscoderInput(inp), TranscoderOutput(out))
    }

    return ImageIO.read(out.toByteArray().inputStream())
}

fun transcode(input: File, multiOutput: Map<Int, File>): Int {
    var updateCount = 0
    for ((size, output) in multiOutput) {
        if (!output.exists() || input.lastModified() != output.lastModified()) {
            output.parentFile.mkdirs()
            val image by lazy { rasterizeSVG(input, (size * 2.1).toInt(), (size * 2.1).toInt()) }
            output.writeImage(image.resized(size, size))
            output.setLastModified(input.lastModified())
            updateCount++
        }
    }
    return updateCount
}

inner class CacheHashes {
    val file = File(project.buildDir, "optimize.properties")
    val props = java.util.Properties().apply {
        try {
            file.inputStream().use {
                load(it)
            }
        } catch (e: Throwable) {
        }
    }

    fun save() {
        file.parentFile.mkdirs()
        try {
            file.outputStream().use {
                props.save(it, "")
            }
        } catch (e: Throwable) {
        }
    }
}

val ch = CacheHashes()

fun ByteArray.hex() = buildString {
    for (b in this@hex) {
        append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
    }
}

fun ByteArray.md5(): ByteArray = MessageDigest.getInstance("MD5").let { md ->
    md.update(this)
    md.digest()
}
fun File.md5() = readBytes().md5()

val sizes = listOf(16, 32, 48, 64, 128, 256, 512)

val transcode = tasks.create<DefaultTask>("transcode") {
    group = "imaging"
    doLast {
        for (file in file("svg").listFiles()) {
            if (file.name.endsWith(".svg")) {
                println("Processing $file...")

                val updateCount = transcode(file("svg/${file.name}"), sizes.associate { size ->
                    size to file("$size/${file.nameWithoutExtension}.png")
                })

                println(" -> Updated($updateCount)")
            }
        }
    }
}

val optimize = tasks.create<DefaultTask>("optimize") {
    dependsOn("transcode")
    doLast {
        for (size in sizes.reversed()) {
            val baseDir = file("$size")
            for (file in baseDir.listFiles()) {
                println("$file...")
                System.out.flush()

                val cacheKey = file.relativeTo(rootDir).path

                if (ch.props[cacheKey] == file.md5().hex()) {
                    println("cached...")
                    continue
                }

                val lastModified = file.lastModified()

                print("  quant...")
                try {
                    exec {
                        commandLine("docker", "run", "-i", "--rm", "-v", "${baseDir.absoluteFile}:/var/workdir/", "kolyadin/pngquant", "--force", "--output", file.name, "--quality", "85-95", file.name)
                    }
                } catch (e: Throwable) {
                }
                print("  ect...")
                try {
                    exec {
                        commandLine("docker", "run", "-i", "--rm", "-v", "${baseDir.absoluteFile}:/data", "soywiz/ect", "-M3", "-strip", file.name)
                    }
                } catch (e: Throwable) {
                }
                println()

                file.setLastModified(lastModified) // Keep previous modification time

                ch.props[cacheKey] = file.md5().hex()
                ch.save()
            }
        }
    }
}

// docker run -it --rm -v $(pwd):/var/workdir/ kolyadin/pngquant --verbose --force --output 512/kmem.png --quality 80-90 512/kmem.png
// docker run -it --rm -v $(pwd):/data/ soywiz/ect -M4 -strip 512/kmem.png
