param([switch]$Test, [switch]$Installer)
$ErrorActionPreference = 'Stop'
$root=$PSScriptRoot; $src=Join-Path $root 'src'; $jar=Join-Path $root 'MagaDrop.jar'
$exe=Join-Path $root 'MagaDrop.exe'; $launcherConfig=Join-Path $root 'launcher\launch4j.xml'
if(-not(Get-Command javac -ErrorAction SilentlyContinue)){throw 'javac não encontrado no PATH.'}
$launch4jCandidates=@(
  (Join-Path ${env:ProgramFiles(x86)} 'Launch4j\launch4jc.exe'),
  (Join-Path $env:ProgramFiles 'Launch4j\launch4jc.exe')
)
$launch4j=$launch4jCandidates|Where-Object{$_ -and (Test-Path -LiteralPath $_)}|Select-Object -First 1
if(-not $launch4j){throw 'Launch4j não encontrado.'}
if(-not(Test-Path -LiteralPath $launcherConfig)){throw "Configuração do launcher não encontrada: $launcherConfig"}
$sources=Get-ChildItem -LiteralPath $src -Filter '*.java'|ForEach-Object FullName
$oldClasses=Get-ChildItem -LiteralPath $src -Filter '*.class'; if($oldClasses){$oldClasses|Remove-Item -Force}
& javac -encoding UTF-8 -d $src $sources
if($LASTEXITCODE -ne 0){throw 'Falha na compilação Java.'}
$classes=Get-ChildItem -LiteralPath $src -Filter '*.class'|ForEach-Object Name
$argsJar=@('--create','--file',$jar,'--main-class','MagaDrop'); foreach($c in $classes){$argsJar+=@('-C',$src,$c)}
& jar @argsJar; if($LASTEXITCODE -ne 0){throw 'Falha ao gerar o JAR.'}
& $launch4j $launcherConfig
if($LASTEXITCODE -ne 0 -or -not(Test-Path -LiteralPath $exe)){throw 'Falha ao gerar o executável Windows.'}
if($Test){
  $testOut=Join-Path ([IO.Path]::GetTempPath()) ('magadrop-test-'+[guid]::NewGuid());New-Item -ItemType Directory -Path $testOut|Out-Null
  try{
    $testSources=Get-ChildItem -LiteralPath (Join-Path $root 'tests') -Filter '*.java'|ForEach-Object FullName
    & javac -encoding UTF-8 -d $testOut $sources $testSources;if($LASTEXITCODE -ne 0){throw 'Falha ao compilar os testes.'}
    & java '-Djava.awt.headless=true' -cp $testOut MagaDropSmokeTest;if($LASTEXITCODE -ne 0){throw 'Os testes falharam.'}
  }finally{Remove-Item -LiteralPath $testOut -Recurse -Force}
}
Write-Host 'Build concluído:' $exe
if($Installer){
  $bundledJava=Join-Path $root 'jre\bin\javaw.exe'
  if(-not(Test-Path -LiteralPath $bundledJava)){throw "Java interno não encontrado: $bundledJava"}
  $isccCandidates=@(
    (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
    (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
  )
  $iscc=$isccCandidates|Where-Object{$_ -and (Test-Path -LiteralPath $_)}|Select-Object -First 1
  if(-not $iscc){throw 'Inno Setup 6 não encontrado.'}
  & $iscc (Join-Path $root 'MagaDrop.iss')
  if($LASTEXITCODE -ne 0){throw 'Falha ao gerar o instalador.'}
  Write-Host 'Instalador concluído:' (Join-Path $root 'output\MagaDropSetup.exe')
}
