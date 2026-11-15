$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$projectDir = Join-Path $repoRoot 'build-isolated\migration-1201\newera'
$gradleWrapper = Join-Path $projectDir 'gradlew.bat'
$initScript = Join-Path $PSScriptRoot 'print-cp.init.gradle'
$outDir = Join-Path $repoRoot 'build-isolated\player-packaging\20261114-r72-standalone\vs-compat'
$buildId = [guid]::NewGuid().ToString('N')
$workDir = Join-Path $outDir "work-$buildId"
$compiledDir = Join-Path $workDir 'classes-mcp'
$classesJar = Join-Path $workDir 'classes-mcp.jar'
$reobfJar = Join-Path $workDir 'classes-srg.jar'
$tempDir = Join-Path $workDir 'temp'
$classpathFile = Join-Path $workDir 'compile-classpath.txt'
$sourceJar = Join-Path $repoRoot 'build-isolated\migration-1201\dependencies\valkyrienskies-120-2.4.11.jar'
$mappingFile = Join-Path $projectDir 'build-isolated\createMcpToSrg\output.tsrg'

if (-not (Test-Path -LiteralPath $gradleWrapper)) { throw "Missing project Gradle wrapper: $gradleWrapper" }
if (-not (Test-Path -LiteralPath $sourceJar)) { throw "Missing locked VS source JAR: $sourceJar" }
New-Item -ItemType Directory -Force -Path $outDir,$workDir,$tempDir | Out-Null

& $gradleWrapper -p $projectDir -PisolatedBuild --system-prop net.minecraftforge.gradle.check.certs=false `
    -I $initScript "-PcompatClasspathFile=$classpathFile" printCompatClasspath --offline -q
if ($LASTEXITCODE -ne 0) { throw 'ForgeGradle classpath or createMcpToSrg preparation failed.' }
if (-not (Test-Path -LiteralPath $mappingFile)) { throw "Missing ForgeGradle createMcpToSrg output: $mappingFile" }

$compileCp = Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8
$mixinJar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.spongepowered\mixin\0.8.5" -Recurse -Filter 'mixin-0.8.5.jar' | Select-Object -First 1 -ExpandProperty FullName
$forgeJar = Join-Path $env:USERPROFILE '.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.28_mapped_official_1.20.1\forge-1.20.1-47.4.28_mapped_official_1.20.1.jar'
$fartJar = Join-Path $env:USERPROFILE '.gradle\caches\forge_gradle\maven_downloader\net\minecraftforge\ForgeAutoRenamingTool\1.0.2\ForgeAutoRenamingTool-1.0.2-all.jar'
foreach ($required in @($mixinJar,$forgeJar,$fartJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing local toolchain dependency: $required" }
}

$apiJar = Join-Path $tempDir 'vs-api.jar'
$mixinExtrasJar = Join-Path $tempDir 'mixinextras-forge-0.4.1.jar'
python -c "import zipfile; z=zipfile.ZipFile(r'$sourceJar'); outer=z.read('META-INF/jars/mixinextras-forge-0.4.1.jar'); import io; m=zipfile.ZipFile(io.BytesIO(outer)); open(r'$apiJar','wb').write(z.read('META-INF/jars/api-1.1.0+cf208d8b56.jar')); open(r'$mixinExtrasJar','wb').write(m.read('META-INF/jars/MixinExtras-0.4.1.jar'))"
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the locked VS API compile dependency.' }


New-Item -ItemType Directory -Force -Path $compiledDir | Out-Null
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$compileClasspath = "$compileCp;$mixinExtrasJar;$apiJar;$sourceJar"
if (-not $compileClasspath.Contains('forge-1.20.1-47.4.28_mapped_official_1.20.1.jar')) {
    throw 'Forge mapped Minecraft classes are missing from the Gradle compile classpath.'
}
foreach ($entry in ($compileClasspath -split ';')) {
    if (-not (Test-Path -LiteralPath $entry)) { throw "Missing compile classpath entry: $entry" }
}
& javac -proc:none -encoding UTF-8 -classpath $compileClasspath -d $compiledDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Could not compile Forge caller-wrapper sources.' }

python -c "import os,zipfile; root=r'$compiledDir'; out=r'$classesJar'; z=zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED); [z.write(os.path.join(dp,f),os.path.relpath(os.path.join(dp,f),root).replace('\\\\','/')) for dp,_,fs in os.walk(root) for f in fs if f.endswith('.class')]; z.close()"
if ($LASTEXITCODE -ne 0) { throw 'Could not create the temporary class archives.' }

$libs = @('--lib',$forgeJar,'--lib',$sourceJar,'--lib',$apiJar,'--lib',$mixinJar,'--lib',$mixinExtrasJar)
& java -jar $fartJar --input $classesJar --output $reobfJar --names $mappingFile @libs --disable-abstract-param
if ($LASTEXITCODE -ne 0) { throw 'Forge Auto Renaming Tool did not complete the SRG remap.' }

$publishedClasses = Join-Path $outDir 'classes-srg.jar'
Copy-Item -LiteralPath $reobfJar -Destination $publishedClasses -Force
Write-Host "SRG-mapped compatibility classes are ready: $publishedClasses"

