{
  description = "Look up what is inside a Smithy model JAR published to Maven";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      eachSystem = nixpkgs.lib.genAttrs systems;

      version = "0.1.0";

      # `nix build` runs without network access, so every JAR sbt needs — its own 2.x bootstrap
      # included — has to be downloaded ahead of time by a fixed-output derivation. Changing the
      # dependencies in build.sbt changes this hash; run `nix build .#deps` to be told the new one.
      depsHash = "sha256-0xJg2TxSnrV3IEaxmvLOoCjVWo+iPPjXpvhOKBb1Qig=";

      forSystem = system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          lib = pkgs.lib;
          jdk = pkgs.temurin-bin-21;

          # Only the inputs that affect the build, so editing the README never rebuilds the jar.
          src = lib.fileset.toSource {
            root = ./.;
            fileset = lib.fileset.unions [
              ./build.sbt
              ./project/build.properties
              ./project/plugins.sbt
              ./src
            ];
          };

          # Everything sbt writes outside the build tree, pointed at paths we control so that the
          # real build can run with a read-only Nix store and no $HOME.
          sbtEnv = ''
            export HOME="$NIX_BUILD_TOP/home"
            export COURSIER_CACHE="$NIX_BUILD_TOP/coursier"
            export JAVA_OPTS="-Duser.home=$HOME -Dfile.encoding=UTF-8"
            export SMIFFER_VERSION="${version}"
            export SBT_OPTS="-Dsbt.boot.directory=$NIX_BUILD_TOP/sbt-boot -Dsbt.global.base=$NIX_BUILD_TOP/sbt-global -Dsbt.ivy.home=$NIX_BUILD_TOP/ivy -Dsbt.ci=true -Dsbt.server.forcestart=false"
            mkdir -p "$HOME" "$COURSIER_CACHE" "$NIX_BUILD_TOP/sbt-boot" "$NIX_BUILD_TOP/sbt-global" "$NIX_BUILD_TOP/ivy"
          '';

          deps = pkgs.stdenvNoCC.mkDerivation {
            pname = "smiffer-deps";
            inherit version src;

            nativeBuildInputs = [ pkgs.sbt jdk ];

            buildPhase = ''
              runHook preBuild
              ${sbtEnv}
              # `Test/compile` as well as `assembly`, so the test-only dependencies are cached too
              # and `nix flake check` does not need the network either.
              sbt --server -batch Test/compile
              sbt --server -batch assembly
              runHook postBuild
            '';

            installPhase = ''
              runHook preInstall
              mkdir -p "$out"
              cp -r "$COURSIER_CACHE" "$out/coursier"
              cp -r "$NIX_BUILD_TOP/sbt-boot" "$out/sbt-boot"

              # Lock files, logs and coursier's TTL marker files carry timestamps and PIDs; leaving
              # them in would make this fixed-output derivation's hash differ run to run.
              find "$out" \( -name '*.lock' -o -name '*.log' -o -name '.*.checked' \
                -o -name 'ttl' -o -name '*.tmp' \) -delete
              find "$out" -type d -empty -delete
              runHook postInstall
            '';

            dontFixup = true;
            outputHashAlgo = "sha256";
            outputHashMode = "recursive";
            outputHash = depsHash;
          };

          smiffer = pkgs.stdenv.mkDerivation {
            pname = "smiffer";
            inherit version src;

            nativeBuildInputs = [ pkgs.sbt jdk pkgs.makeWrapper ];

            buildPhase = ''
              runHook preBuild
              ${sbtEnv}
              # The prefetched caches are copied rather than symlinked: sbt and coursier both write
              # into them, and the Nix store is read-only.
              cp -r --no-preserve=mode,ownership ${deps}/coursier/. "$COURSIER_CACHE/"
              cp -r --no-preserve=mode,ownership ${deps}/sbt-boot/. "$NIX_BUILD_TOP/sbt-boot/"

              sbt --server -batch assembly
              runHook postBuild
            '';

            installPhase = ''
              runHook preInstall
              mkdir -p "$out/share/smiffer"
              cp "$(find target -name smiffer.jar -print -quit)" "$out/share/smiffer/smiffer.jar"

              makeWrapper ${lib.getExe' jdk "java"} "$out/bin/smiffer" \
                --add-flags "-jar $out/share/smiffer/smiffer.jar"
              runHook postInstall
            '';

            doCheck = true;
            checkPhase = ''
              runHook preCheck
              ${sbtEnv}
              sbt --server -batch test
              runHook postCheck
            '';

            meta = {
              description = "Look up what is inside a Smithy model JAR published to Maven";
              mainProgram = "smiffer";
              platforms = [ system ];
            };
          };
        in
        { inherit deps smiffer pkgs jdk; };
    in
    {
      packages = eachSystem (system:
        let built = forSystem system;
        in {
          default = built.smiffer;
          smiffer = built.smiffer;
          # Exposed so the dependency hash can be regenerated: `nix build .#deps`.
          deps = built.deps;
        });

      apps = eachSystem (system: {
        default = {
          type = "app";
          program = "${nixpkgs.lib.getExe (forSystem system).smiffer}";
        };
      });

      devShells = eachSystem (system:
        let built = forSystem system;
        in {
          default = built.pkgs.mkShell {
            packages = [ built.pkgs.sbt built.jdk ];
            shellHook = ''
              echo "smiffer dev shell — 'sbt test', 'sbt assembly', then 'java -jar target/out/jvm/scala-3.9.0/smiffer/smiffer.jar'"
            '';
          };
        });

      overlays.default = final: _prev: {
        smiffer = (forSystem final.system).smiffer;
      };
    };
}
