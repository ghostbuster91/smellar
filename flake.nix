{
  description = "Look up what is inside a Smithy model JAR published to Maven";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    scala-cli-nix = {
      url = "github:ghostbuster91/scala-cli-nix/sbt-support";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, scala-cli-nix }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      eachSystem = nixpkgs.lib.genAttrs systems;

      # `scala.lock.json` records the jar path sbt will produce, and that path
      # embeds the version — so this has to match the SMELLAR_VERSION the
      # lockfile was generated with. Bumping it means re-running
      # `scn lock-sbt` (see "Updating the lockfile" in the README).
      version = "0.1.0";

      forSystem = system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ scala-cli-nix.overlays.default ];
          };
          jdk = pkgs.temurin-bin-21;

          smellar = pkgs.scala-cli-nix.buildSbtApp {
            pname = "smellar";
            inherit version;
            src = ./.;
            lockFile = ./scala.lock.json;

            # sbt runs inside the Nix sandbox against the locked toolchain, so
            # it reads this exactly as it does on a developer's machine. It
            # must agree with what the lockfile was generated with.
            attrOverrides = attrs: _platform: attrs // {
              SMELLAR_VERSION = version;
              meta = (attrs.meta or { }) // {
                description = "Look up what is inside a Smithy model JAR published to Maven";
                mainProgram = "smellar";
                platforms = [ system ];
              };
            };
          };
        in
        { inherit smellar pkgs jdk; };
    in
    {
      packages = eachSystem (system:
        let built = forSystem system;
        in {
          default = built.smellar;
          smellar = built.smellar;
        });

      apps = eachSystem (system: {
        default = {
          type = "app";
          program = "${nixpkgs.lib.getExe (forSystem system).smellar}";
          meta.description = "Look up what is inside a Smithy model JAR published to Maven";
        };
      });

      # `sbt smellar/test` run offline in the sandbox, exposed by buildSbtApp
      # as passthru.tests. collectChecks flattens that into checks.<system>.
      # Only the `smellar` attribute is passed, not all of `packages`: `default`
      # is the same derivation under a second name, and collecting both would
      # just list the one test twice.
      checks = eachSystem (system:
        let built = forSystem system;
        in built.pkgs.scala-cli-nix.collectChecks {
          inherit (self.packages.${system}) smellar;
        });

      devShells = eachSystem (system:
        let built = forSystem system;
        in {
          default = built.pkgs.mkShell {
            # Deliberately without the scala-cli-nix CLI: it is only needed when
            # the lockfile changes, and pulling it in here would make everyone
            # entering the shell compile it first (and it needs a bundled
            # scala-cli fork that isn't published for every system). The README
            # documents the `nix run` form instead.
            packages = [ built.pkgs.sbt built.jdk ];
            shellHook = ''
              echo "smellar dev shell — 'sbt test', 'sbt assembly', then 'java -jar target/out/jvm/scala-3.9.0/smellar/smellar.jar'"
            '';
          };
        });

      overlays.default = final: _prev: {
        smellar = (forSystem final.system).smellar;
      };
    };
}
