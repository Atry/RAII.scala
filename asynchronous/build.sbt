libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

scalacOptions += "-Ypartial-unification"

scalacOptions += "-Xexperimental" // Enable SAM types on Scala 2.11

libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.2"

libraryDependencies += "com.thoughtworks.tryt" %% "covariant" % "2.0.3"

libraryDependencies += "com.thoughtworks.future" %% "future" % "2.0.0-M2"

enablePlugins(Example)

exampleSuperTypes ~= { oldExampleSuperTypes =>
  import oldExampleSuperTypes._
  updated(indexOf("_root_.org.scalatest.FreeSpec"), "_root_.org.scalatest.AsyncFreeSpec")
}

exampleSuperTypes += "_root_.com.thoughtworks.raii.scalatest.ScalazTaskToScalaFuture"
