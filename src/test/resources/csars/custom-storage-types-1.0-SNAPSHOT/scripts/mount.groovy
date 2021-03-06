import org.cloudifysource.utilitydomain.context.ServiceContextFactory

def context = ServiceContextFactory.getServiceContext()
def builder = new AntBuilder()
builder.sequential {
  chmod(dir:"${context.serviceDirectory}/scripts", perm:"+x", includes:"*.sh")
  mkdir(dir: location)
  echo(message:"mount.groovy: Running ${context.serviceDirectory}/scripts/mountStorage.sh...")
  exec(executable: "${context.serviceDirectory}/scripts/mountStorage.sh",failonerror: "true") {
    arg(value:"${device}")
    arg(value:"${location}")
  }
}

return location