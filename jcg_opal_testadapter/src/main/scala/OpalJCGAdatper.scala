import java.io.File
import java.io.FileWriter
import java.net.URL

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.opalj.br.DeclaredMethod
import org.opalj.br.Code
import org.opalj.br.analyses.Project
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.analyses.Project.JavaClassFileReader
import org.opalj.br.instructions.MethodInvocationInstruction
import org.opalj.fpcf.PropertyStoreKey
import org.opalj.fpcf.FPCFAnalysesManagerKey
import org.opalj.fpcf.FinalEP
import org.opalj.fpcf.analyses.cg.EagerRTACallGraphAnalysisScheduler
import org.opalj.fpcf.analyses.cg.EagerSerializationRelatedCallsAnalysis
import org.opalj.fpcf.analyses.cg.EagerThreadRelatedCallsAnalysis
import org.opalj.fpcf.analyses.cg.EagerLoadedClassesAnalysis
import org.opalj.fpcf.analyses.cg.EagerFinalizerAnalysisScheduler
import org.opalj.fpcf.analyses.cg.LazyCalleesAnalysis
import org.opalj.fpcf.analyses.cg.EagerReflectionRelatedCallsAnalysis
import org.opalj.fpcf.properties.ThreadRelatedCallees
import org.opalj.fpcf.properties.StandardInvokeCallees
import org.opalj.fpcf.properties.Callees
import org.opalj.fpcf.properties.SerializationRelatedCallees
import org.opalj.fpcf.properties.NoCallees
import org.opalj.fpcf.properties.NoCalleesDueToNotReachableMethod
import org.opalj.fpcf.properties.ReflectionRelatedCallees
import play.api.libs.json.Json
import scala.collection.JavaConverters._

object OpalJCGAdatper extends JCGTestAdapter {

    def possibleAlgorithms(): Array[String] = Array[String]("RTA")

    def frameworkName(): String = "OPAL"

    def serializeCG(
        algorithm:    String,
        target:       String,
        mainClass:    String,
        classPath:    Array[String],
        jreLocations: String,
        jreVersion:   Int,
        outputFile:   String
    ): Long = {
        val before = System.nanoTime()
        val baseConfig: Config = ConfigFactory.load().withValue(
            "org.opalj.br.reader.ClassFileReader.Invokedynamic.rewrite",
            ConfigValueFactory.fromAnyRef(true)
        )

        implicit val config: Config =
            if (mainClass eq null) {
                baseConfig.withValue(
                    "org.opalj.br.analyses.cg.InitialEntryPointsKey.analysis",
                    ConfigValueFactory.fromAnyRef("org.opalj.br.analyses.cg.LibraryEntryPointsFinder")
                )
            } else {
                baseConfig.withValue(
                    "org.opalj.br.analyses.cg.InitialEntryPointsKey.analysis",
                    ConfigValueFactory.fromAnyRef("org.opalj.br.analyses.cg.ConfigurationEntryPointsFinder")
                ).withValue(
                        "org.opalj.br.analyses.cg.InitialEntryPointsKey.entryPoints",
                        ConfigValueFactory.fromIterable(Seq(ConfigValueFactory.fromMap(Map(
                            "declaringClass" → mainClass.replace('.', '/'), "name" → "main"
                        ).asJava)).asJava)
                    )
            }

        val cfReader = JavaClassFileReader(theConfig = config)
        val targetClassFiles = cfReader.ClassFiles(new File(target)).toIterator
        val cpClassFiles = cfReader.AllClassFiles(classPath.map(new File(_))).toIterator
        val jreJars = JRELocation.mapping(new File(jreLocations)).getOrElse(jreVersion, throw new IllegalArgumentException("unspecified java version"))
        val jre = cfReader.AllClassFiles(jreJars)
        val allClassFiles = targetClassFiles ++ cpClassFiles ++ jre
        val project: Project[URL] = Project(allClassFiles.toTraversable, Seq.empty, true, Seq.empty)

        val ps = project.get(PropertyStoreKey)

        val manager = project.get(FPCFAnalysesManagerKey)
        manager.runAll(
            EagerRTACallGraphAnalysisScheduler,
            EagerLoadedClassesAnalysis,
            EagerFinalizerAnalysisScheduler,
            EagerThreadRelatedCallsAnalysis,
            EagerSerializationRelatedCallsAnalysis,
            EagerReflectionRelatedCallsAnalysis,
            new LazyCalleesAnalysis(
                Set(
                    StandardInvokeCallees,
                    ThreadRelatedCallees,
                    SerializationRelatedCallees,
                    ReflectionRelatedCallees
                )
            )
        )

        implicit val declaredMethods = project.get(DeclaredMethodsKey)
        for (dm ← declaredMethods.declaredMethods) {
            ps.force(dm, Callees.key)
        }

        ps.waitOnPhaseCompletion()

        val after = System.nanoTime()

        var reachableMethods = Set.empty[ReachableMethod]

        for (
            dm ← declaredMethods.declaredMethods
            //TODO THIS IS BROKEN FIX IT
            if((!dm.hasSingleDefinedMethod && !dm.hasMultipleDefinedMethods) ||
                (dm.hasSingleDefinedMethod && dm.definedMethod.classFile.thisType == dm.declaringClassType))
        ) {
            val m = createMethodObject(dm)
            ps(dm, Callees.key) match {
                case FinalEP(_, NoCalleesDueToNotReachableMethod) ⇒
                case FinalEP(_, NoCallees) ⇒
                    reachableMethods += ReachableMethod(m, Set.empty)
                case FinalEP(_, cs: Callees) ⇒
                    val body = dm.definedMethod.body.get
                    val callSites = cs.callees.flatMap {
                        case (pc, callees) ⇒
                            createCallSites(body, pc, callees)
                    }.toSet
                    reachableMethods += ReachableMethod(m, callSites)
            }
        }

        println(reachableMethods.size)

        ps.shutdown()

        val file: FileWriter = new FileWriter(outputFile)
        file.write(Json.prettyPrint(Json.toJson(ReachableMethods(reachableMethods))))
        file.flush()
        file.close()

        after - before
    }

    private def createCallSites(
        body:    Code,
        pc:      Int,
        callees: Set[DeclaredMethod]
    ): Seq[CallSite] = {
        val declaredO = body.instructions(pc) match {
            case MethodInvocationInstruction(dc, _, name, desc) ⇒ Some(dc, name, desc)
            case _                                              ⇒ None
        }

        val line = body.lineNumber(pc).getOrElse(-1)

        if (declaredO.isDefined) {
            val (dc, name, desc) = declaredO.get
            val declaredTarget =
                Method(
                    name,
                    dc.toJVMTypeName,
                    desc.returnType.toJVMTypeName,
                    desc.parameterTypes.iterator.map(_.toJVMTypeName).toList
                )

            val (directCallees, indirectCallees) = callees.partition { callee ⇒
                callee.name == name && // TODO check descriptor correctly for refinement
                    callee.descriptor.parametersCount == desc.parametersCount
            }

            indirectCallees.iterator.map(createIndividualCallSite(_, line)).toSeq :+
                CallSite(
                    declaredTarget,
                    line,
                    directCallees.iterator.map(createMethodObject).toSet
                )
        } else {
            callees.iterator.map(createIndividualCallSite(_, line)).toSeq
        }
    }

    def createIndividualCallSite(
        method: DeclaredMethod,
        line:   Int
    ): CallSite = {
        CallSite(
            createMethodObject(method),
            line,
            Set(createMethodObject(method))
        )
    }

    private def createMethodObject(method: DeclaredMethod): Method = {
        Method(
            method.name,
            method.declaringClassType.toJVMTypeName,
            method.descriptor.returnType.toJVMTypeName,
            method.descriptor.parameterTypes.iterator.map(_.toJVMTypeName).toList
        )
    }
}