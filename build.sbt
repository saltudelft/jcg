javacOptions in ThisBuild ++= Seq("-parameters")

lazy val commonSettings = Seq(
    scalaVersion := "2.12.6",
    organization := "de.tu-darmstadt.stg",
    resolvers += Resolver.sonatypeRepo("snapshots"),
    version := "0.1"
)

lazy val jcg_annotations = project.settings(
    commonSettings,
    aggregate in assembly := false
)

lazy val jcg_annotation_matcher = project.settings(
    commonSettings,
    aggregate in assembly := false
).dependsOn(jcg_annotations, jcg_testadapter_commons)

lazy val jcg_wala_testadapter = project.settings(
    commonSettings,
    aggregate in assembly := false
).dependsOn(jcg_testadapter_commons)

lazy val jcg_soot_testadapter = project.settings(
    commonSettings,
    aggregate in assembly := false
).dependsOn(jcg_testadapter_commons)

//lazy val jcg_opal_testadapter = project.settings(
//    commonSettings,
//    aggregate in assembly := false
//).dependsOn(jcg_testadapter_commons)

lazy val jcg_doop_testadapter = project.settings(
    commonSettings,
    aggregate in assembly := false
).dependsOn(
    jcg_annotation_matcher,
    jcg_testadapter_commons
)

lazy val jcg_testadapter_commons = project.settings(
    commonSettings,
    aggregate in assembly := false
)

lazy val jcg_evaluation = project.settings(
    commonSettings
).dependsOn(
    jcg_annotations,
    jcg_annotation_matcher,
//    jcg_opal_testadapter,
    jcg_wala_testadapter,
    jcg_soot_testadapter
)