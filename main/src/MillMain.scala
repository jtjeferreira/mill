package mill

import java.io.{InputStream, PrintStream}
import java.util.Locale

import scala.collection.JavaConverters._

import ammonite.main.Cli._
import io.github.retronym.java9rtexport.Export
import mill.eval.Evaluator
import mill.api.DummyInputStream

object MillMain {

  def main(args: Array[String]): Unit = {
    // Remove the trailing interactive parameter, we already handled it on call site
    val as = args match {
      case Array(s, _*) if s == "-i" || s == "--interactive" => args.tail
      case _ => args
    }
    val (result, _) = main0(
      as,
      None,
      ammonite.Main.isInteractive(),
      System.in,
      System.out,
      System.err,
      System.getenv().asScala.toMap,
      b => (),
      initialSystemProperties = Map()
    )
    System.exit(if (result) 0 else 1)
  }

  def main0(
    args: Array[String],
    stateCache: Option[Evaluator.State],
    mainInteractive: Boolean,
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    env: Map[String, String],
    setIdle: Boolean => Unit,
    initialSystemProperties: Map[String, String]
  ): (Boolean, Option[Evaluator.State]) = {
    import ammonite.main.Cli

    val millHome = mill.api.Ctx.defaultHome

    val removed = Set("predef-code", "no-home-predef")

    var interactive = false
    val interactiveSignature = Arg[Config, Unit](
      "interactive", Some('i'),
      "Run Mill in interactive mode, suitable for opening REPLs and taking user input. In this mode, no mill server will be used.",
      (c, v) => {
        interactive = true
        c
      }
    )

    var showVersion = false
    val showVersionSignature = Arg[Config, Unit](
      name = "version", Some('v'),
      doc = "Show mill version and exit.",
      (c, v) => {
        showVersion = true
        c
      }
    )

    var disableTicker = false
    val disableTickerSignature = Arg[Config, Unit](
      name = "disable-ticker", shortName = None,
      doc = "Disable ticker log (e.g. short-lived prints of stages and progress bars)",
      action = (c, v) => {
      disableTicker = true
      c
    }
    )

    var debugLog = false
    val debugLogSignature = Arg[Config, Unit](
      name = "debug", shortName = Some('d'),
      doc = "Show debug output on STDOUT",
      action = (c, v) => {
      debugLog = true
      c
    }
    )

    var keepGoing = false
    val keepGoingSignature = Arg[Config, Unit] (
      name = "keep-going", shortName = Some('k'), doc = "Continue build, even after build failures",
      (c,v) => {
        keepGoing = true
        c
      }
    )

    var extraSystemProperties = Map[String, String]()
    val extraSystemPropertiesSignature = Arg[Config, String](
      name = "define", shortName = Some('D'),
      doc = "Define (or overwrite) a system property",
      action = { (c, v) =>
      extraSystemProperties += (v.split("[=]", 2) match {
        case Array(k, v) => k -> v
        case Array(k) => k -> ""
      })
      c
    }
    )

    var threadCount: Option[Int] = Some(1)
    val threadCountSignature = Arg[Config, Int](
      name = "jobs", Some('j'),
      doc = "Allow processing N targets in parallel. Use 1 to disable parallel and 0 to use as much threads as available processors.",
      (c, v) => {
        threadCount = if(v == 0) None else Some(v)
        c
      }
    )

    val millArgSignature =
      Cli.genericSignature.filter(a => !removed(a.name)) ++
        Seq(
          interactiveSignature,
          showVersionSignature,
          disableTickerSignature,
          debugLogSignature,
          keepGoingSignature,
          extraSystemPropertiesSignature,
          threadCountSignature
          )

    Cli.groupArgs(
      args.toList,
      millArgSignature,
      Cli.Config(home = millHome, remoteLogging = false)
    ) match {
      case _ if interactive =>
        // because this parameter was handled earlier (when in first position),
        // here it is too late and we can't handle it properly.
        stderr.println("-i/--interactive must be passed in as the first argument")
        (false, None)
      case Left(msg) =>
        stderr.println(msg)
        (false, None)
      case Right((cliConfig, _)) if cliConfig.help =>
        val leftMargin = millArgSignature.map(ammonite.main.Cli.showArg(_).length).max + 2
        stdout.println(
          s"""Mill Build Tool
             |usage: mill [mill-options] [target [target-options]]
             |
             |${formatBlock(millArgSignature, leftMargin).mkString(ammonite.util.Util.newLine)}""".stripMargin
        )
        (true, None)
      case Right(_) if showVersion =>
        def p(k: String, d: String = "<unknown>") = System.getProperty(k, d)
        stdout.println(
          s"""Mill Build Tool version ${p("MILL_VERSION", "<unknown mill version>")}
             |Java version: ${p("java.version", "<unknown Java version")}, vendor: ${p("java.vendor", "<unknown Java vendor")}, runtime: ${p("java.home", "<unknown runtime")}
             |Default locale: ${Locale.getDefault()}, platform encoding: ${p("file.encoding", "<unknown encoding>")}
             |OS name: "${p("os.name")}", version: ${p("os.version")}, arch: ${p("os.arch")}""".stripMargin
        )
        (true, None)

      case Right((cliConfig, leftoverArgs)) =>

        val repl = leftoverArgs.isEmpty
        if (repl && stdin == DummyInputStream) {
          stderr.println("Build repl needs to be run with the -i/--interactive flag")
          (false, stateCache)
        }else{
          val systemProps = initialSystemProperties ++ extraSystemProperties

          val config =
            if(!repl) cliConfig
            else cliConfig.copy(
              predefCode =
                s"""import $$file.build, build._
                  |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                  |  os.Path(${pprint.apply(cliConfig.home.toIO.getCanonicalPath.replaceAllLiterally("$", "$$")).plainText}),
                  |  $disableTicker,
                  |  interp.colors(),
                  |  repl.pprinter(),
                  |  build.millSelf.get,
                  |  build.millDiscover,
                  |  debugLog = $debugLog,
                  |  keepGoing = $keepGoing,
                  |  systemProperties = ${systemProps},
                  |  threadCount = $threadCount
                  |)
                  |repl.pprinter() = replApplyHandler.pprinter
                  |import replApplyHandler.generatedEval._
                  |
                """.stripMargin,
                welcomeBanner = None
              )

            val runner = new mill.main.MainRunner(
              config.copy(colored = config.colored orElse Option(mainInteractive)),
              disableTicker,
              stdout, stderr, stdin,
              stateCache,
              env,
              setIdle,
              debugLog = debugLog,
              keepGoing = keepGoing,
              systemProperties = systemProps,
              threadCount = threadCount
            )

            if (mill.main.client.Util.isJava9OrAbove) {
              val rt = cliConfig.home / Export.rtJarName
              if (!os.exists(rt)) {
                runner.printInfo(s"Preparing Java ${System.getProperty("java.version")} runtime; this may take a minute or two ...")
                Export.rtTo(rt.toIO, false)
              }
            }

            if (repl) {
              runner.printInfo("Loading...")
              (runner.watchLoop(isRepl = true, printing = false, _.run()), runner.stateCache)
            } else {
              (runner.runScript(os.pwd / "build.sc", leftoverArgs), runner.stateCache)
            }
          }

      }
  }
}
