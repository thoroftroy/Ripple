#[cfg(target_os = "linux")]
mod cli;

#[cfg(target_os = "linux")]
fn main() {
    let code = cli::main();
    std::process::exit(code);
}

#[cfg(not(target_os = "linux"))]
fn main() {}
