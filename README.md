This tool allows opening files in intellij similar to jetbrains toolbox open function but instead more useful.

the Toolbox link require a set path which is annoying if you have auto generated classes etc. So instead when opening the link it will search through your entire project by name and will check whether the package and name matches and will then proceed to open it via that jetbrains toolbox link.

scheme example:     http://localhost:9090/open?project=BBsentials-Server&class=de.hype.bbsentials.server.discord.events.staticimplementations.commands.commands.commandgroups.bingoeventcommandgroup.Top100

you can also specify &line={line} at the end for optional line.

Keep in mind that I hacked this together relatively quickly with low effort, but wanted to publish this as a small useful gadget. I will NOT maintain this, but merge pull requests that I see a Reason for.
If you want something etc make it yourself and make a pr. Make sure its generally useful and not specific for you only (
aka no project binding etc)

## IMPORTANT:
- This tool is probably linux only. I do NOT care about windows or mac. go get a good OS.
- This tool injects itself into auto start for convenience
- This tool will intercept all http and https links opened with xdg-open internally (aka when you click a link, and it opens in browser). all links but localhost 9090 are forwarded to the default that was previous with https. 9090 is silently dropped and sent via curl which means that you dont get the annoying popup that instantly closes in your browser.
- This tool is low effort and might be insecure. I tried to avoid command injections. (also localhost only)
- This tool depends on wmcrtl and xdotool (install via `apt install wmcrtl xdotool`) as well as [Jetbrains Toolbox](<https://www.jetbrains.com/de-de/lp/toolbox/>)

Example Usage:
  ![grafik](https://github.com/user-attachments/assets/b54020f3-6f59-4b51-b5e7-2c519c643109)
  If you click these links they will be opened in your local intelij. I unfortunately can't support dependencies.
