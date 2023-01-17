package com.app.arbrochuredemoapp

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentOnAttachListener
import com.google.android.filament.Engine
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.filamat.MaterialPackage
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Sceneform
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.EngineInstance
import com.google.ar.sceneform.rendering.ExternalTexture
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.rendering.RenderableInstance
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.BaseArFragment
import com.google.ar.sceneform.ux.InstructionsController
import com.google.ar.sceneform.ux.TransformableNode
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer


class MainActivity : AppCompatActivity(), FragmentOnAttachListener,
    BaseArFragment.OnSessionConfigurationListener {
    private val futures: MutableList<CompletableFuture<Void>> = ArrayList()
    private var arFragment: ArFragment? = null
    private var matrixDetected = false
    private var rabbitDetected = false
    private var database: AugmentedImageDatabase? = null
    private var plainVideoModel: Renderable? = null
    private var plainVideoMaterial: Material? = null
    private var mediaPlayer: MediaPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v: View?, insets: WindowInsetsCompat ->
            (toolbar.layoutParams as MarginLayoutParams).topMargin = insets
                .getInsets(WindowInsetsCompat.Type.systemBars()).top
            WindowInsetsCompat.CONSUMED
        }
        supportFragmentManager.addFragmentOnAttachListener(this)
        if (savedInstanceState == null) {
            if (Sceneform.isSupported(this)) {
                supportFragmentManager.beginTransaction()
                    .add(R.id.arFragment, ArFragment::class.java, null)
                    .commit()
            }
        }
        if (Sceneform.isSupported(this)) {
            // .glb models can be loaded at runtime when needed or when app starts
            // This method loads ModelRenderable when app starts
            loadMatrixModel()
            loadMatrixMaterial()
        }
    }

    override fun onAttachFragment(fragmentManager: FragmentManager, fragment: Fragment) {
        if (fragment.id == R.id.arFragment) {
            arFragment = fragment as ArFragment
            arFragment.setOnSessionConfigurationListener(this)
        }
    }

    fun onSessionConfiguration(session: Session?, config: Config) {
        // Disable plane detection
        config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)

        // Images to be detected by our AR need to be added in AugmentedImageDatabase
        // This is how database is created at runtime
        // You can also prebuild database in you computer and load it directly (see: https://developers.google.com/ar/develop/java/augmented-images/guide#database)
        database = AugmentedImageDatabase(session)
        val matrixImage = BitmapFactory.decodeResource(resources, R.drawable.matrix)
        val rabbitImage = BitmapFactory.decodeResource(resources, R.drawable.rabbit)
        // Every image has to have its own unique String identifier
        database.addImage("matrix", matrixImage)
        database.addImage("rabbit", rabbitImage)
        config.setAugmentedImageDatabase(database)

        // Check for image detection
        arFragment.setOnAugmentedImageUpdateListener { augmentedImage: AugmentedImage ->
            onAugmentedImageTrackingUpdate(
                augmentedImage
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        futures.forEach(Consumer { future: CompletableFuture<Void> ->
            if (!future.isDone) future.cancel(
                true
            )
        })
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
        }
    }

    private fun loadMatrixModel() {
        futures.add(ModelRenderable.builder()
            .setSource(this, Uri.parse("models/Video.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { model ->
                //removing shadows for this Renderable
                model.setShadowCaster(false)
                model.setShadowReceiver(true)
                plainVideoModel = model
            }
            .exceptionally { throwable ->
                Toast.makeText(this, "Unable to load renderable", Toast.LENGTH_LONG).show()
                null
            })
    }

    private fun loadMatrixMaterial() {
        val filamentEngine: Engine = EngineInstance.getEngine().getFilamentEngine()
        MaterialBuilder.init()
        val materialBuilder: MaterialBuilder = MaterialBuilder()
            .platform(MaterialBuilder.Platform.MOBILE)
            .name("External Video Material")
            .require(MaterialBuilder.VertexAttribute.UV0)
            .shading(MaterialBuilder.Shading.UNLIT)
            .doubleSided(true)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_EXTERNAL,
                MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT,
                "videoTexture"
            )
            .optimization(MaterialBuilder.Optimization.NONE)
        val plainVideoMaterialPackage: MaterialPackage = materialBuilder
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .material(
                """void material(inout MaterialInputs material) {
    prepareMaterial(material);
    material.baseColor = texture(materialParams_videoTexture, getUV0()).rgba;
}
"""
            )
            .build(filamentEngine)
        if (plainVideoMaterialPackage.isValid()) {
            val buffer: ByteBuffer = plainVideoMaterialPackage.getBuffer()
            futures.add(Material.builder()
                .setSource(buffer)
                .build()
                .thenAccept { material -> plainVideoMaterial = material }
                .exceptionally { throwable ->
                    Toast.makeText(this, "Unable to load material", Toast.LENGTH_LONG).show()
                    null
                })
        }
        MaterialBuilder.shutdown()
    }

    fun onAugmentedImageTrackingUpdate(augmentedImage: AugmentedImage) {
        // If there are both images already detected, for better CPU usage we do not need scan for them
        if (matrixDetected && rabbitDetected) {
            return
        }
        if (augmentedImage.getTrackingState() === TrackingState.TRACKING
            && augmentedImage.getTrackingMethod() === AugmentedImage.TrackingMethod.FULL_TRACKING
        ) {

            // Setting anchor to the center of Augmented Image
            val anchorNode = AnchorNode(augmentedImage.createAnchor(augmentedImage.getCenterPose()))

            // If matrix video haven't been placed yet and detected image has String identifier of "matrix"
            if (!matrixDetected && augmentedImage.getName().equals("matrix")) {
                matrixDetected = true
                Toast.makeText(this, "Matrix tag detected", Toast.LENGTH_LONG).show()

                // AnchorNode placed to the detected tag and set it to the real size of the tag
                // This will cause deformation if your AR tag has different aspect ratio than your video
                anchorNode.setWorldScale(
                    Vector3(
                        augmentedImage.getExtentX(),
                        1f,
                        augmentedImage.getExtentZ()
                    )
                )
                arFragment.getArSceneView().getScene().addChild(anchorNode)
                val videoNode = TransformableNode(arFragment.getTransformationSystem())
                // For some reason it is shown upside down so this will rotate it correctly
                videoNode.setLocalRotation(Quaternion.axisAngle(Vector3(0, 1f, 0), 180f))
                anchorNode.addChild(videoNode)

                // Setting texture
                val externalTexture = ExternalTexture()
                val renderableInstance: RenderableInstance =
                    videoNode.setRenderable(plainVideoModel)
                renderableInstance.setMaterial(plainVideoMaterial)

                // Setting MediaPLayer
                renderableInstance.getMaterial().setExternalTexture("videoTexture", externalTexture)
                mediaPlayer = MediaPlayer.create(this, R.raw.matrix)
                mediaPlayer.setLooping(true)
                mediaPlayer.setSurface(externalTexture.getSurface())
                mediaPlayer.start()
            }
            // If rabbit model haven't been placed yet and detected image has String identifier of "rabbit"
            // This is also example of model loading and placing at runtime
            if (!rabbitDetected && augmentedImage.getName().equals("rabbit")) {
                rabbitDetected = true
                Toast.makeText(this, "Rabbit tag detected", Toast.LENGTH_LONG).show()
                anchorNode.setWorldScale(Vector3(3.5f, 3.5f, 3.5f))
                arFragment.getArSceneView().getScene().addChild(anchorNode)
                futures.add(ModelRenderable.builder()
                    .setSource(this, Uri.parse("models/Rabbit.glb"))
                    .setIsFilamentGltf(true)
                    .build()
                    .thenAccept { rabbitModel ->
                        val modelNode =
                            TransformableNode(arFragment.getTransformationSystem())
                        modelNode.setRenderable(rabbitModel)
                        anchorNode.addChild(modelNode)
                    }
                    .exceptionally { throwable ->
                        Toast.makeText(this, "Unable to load rabbit model", Toast.LENGTH_LONG)
                            .show()
                        null
                    })
            }
        }
        if (matrixDetected && rabbitDetected) {
            arFragment.getInstructionsController().setEnabled(
                InstructionsController.TYPE_AUGMENTED_IMAGE_SCAN, false
            )
        }
    }
}