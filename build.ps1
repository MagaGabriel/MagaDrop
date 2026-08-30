param([switch]$Test)
$ErrorActionPreference = 'Stop'
$root=$PSScriptRoot; $src=Join-Path $root 'src'; $jar=Join-Path $root 'MagaDrop.jar'
$exe=Join-Path $root 'MagaDrop.exe'; $stub=Join-Path $root 'launcher\MagaDropLauncher.bin'
if(-not(Get-Command javac -ErrorAction SilentlyContinue)){throw 'javac não encontrado no PATH.'}
if(-not(Test-Path -LiteralPath $stub)){throw "Launcher não encontrado: $stub"}
& javac -encoding UTF-8 -d $src (Join-Path $src 'Splash.java') (Join-Path $src 'MagaDrop.java')
if($LASTEXITCODE -ne 0){throw 'Falha na compilação Java.'}
$classes=@('MagaDrop.class','MagaDrop$PaginaHandler.class','MagaDrop$UploadHandler.class','MagaDrop$UploadMuitoGrandeException.class','Splash.class')
$argsJar=@('--create','--file',$jar,'--main-class','MagaDrop'); foreach($c in $classes){$argsJar+=@('-C',$src,$c)}
& jar @argsJar; if($LASTEXITCODE -ne 0){throw 'Falha ao gerar o JAR.'}
$out=[IO.File]::Create($exe)
try{foreach($part in @($stub,$jar)){$input=[IO.File]::OpenRead($part);try{$input.CopyTo($out)}finally{$input.Dispose()}}}finally{$out.Dispose()}
if($Test){
  $testOut=Join-Path ([IO.Path]::GetTempPath()) ('magadrop-test-'+[guid]::NewGuid());New-Item -ItemType Directory -Path $testOut|Out-Null
  try{
    & javac -encoding UTF-8 -d $testOut (Join-Path $src 'MagaDrop.java') (Join-Path $src 'Splash.java') (Join-Path $root 'tests\MagaDropSmokeTest.java');if($LASTEXITCODE -ne 0){throw 'Falha ao compilar os testes.'}
    & java '-Djava.awt.headless=true' -cp $testOut MagaDropSmokeTest;if($LASTEXITCODE -ne 0){throw 'Os testes falharam.'}
  }finally{Remove-Item -LiteralPath $testOut -Recurse -Force}
}
Write-Host 'Build concluído:' $exe
