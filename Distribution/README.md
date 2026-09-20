# ClinicOS — Getting Started

## What This Is

ClinicOS is a clinic staff-management application that runs entirely on your computer. This package contains everything you need to run the application locally — nothing is sent over the internet. Once started, you'll access it at `http://localhost:8080` in your web browser.

## Requirements

Before you begin, you'll need:

- **Windows 10 or 11**
- **Docker Desktop** installed (download from https://www.docker.com/products/docker-desktop/ — if the installer asks about WSL2, enable it)
- **About 4 GB of free disk space**
- **Port 8080 free** (this is a network address your computer uses; if something else is already using it, you can change it later in this guide)
- **Do NOT need:** Git, Java, Maven, Node.js, or source code — none of that

Docker Desktop does **not** need to be open before you run the scripts below. If it's installed but not running, the scripts will start it automatically.

## What's in This Folder

- `app-image.tar` — the application itself; do not delete or edit
- `docker-compose.yml` — defines how the application pieces fit together; do not edit unless instructed
- `.env.example` — a template for settings; copy this if needed
- `.env` — your settings file (port number, database passwords, etc.); safe to edit
- `start.ps1` — the script that starts everything
- `stop.ps1` — the script that stops everything
- `reset.ps1` — the script that erases all data and starts fresh (asks for confirmation first)
- `common.ps1` — used internally by the other scripts; do not run directly
- `initdb/` — database setup files; used automatically on first start; do not touch
- `README.md` — this file

## First Run

**Open a PowerShell window in this folder:**
- On Windows 11: right-click in an empty area of the folder → select "Open in Terminal" → PowerShell will open
- On Windows 10: hold Shift, right-click in an empty area → select "Open PowerShell window here"

**Type the command and press Enter:**
```
.\start.ps1
```

**First run takes 1–3 minutes** because the database is being set up. Later runs are much faster.

**If PowerShell shows "running scripts is disabled on this system":**
1. In that same PowerShell window, type: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`
2. Press Enter
3. Type: `.\start.ps1`
4. Press Enter again

When everything is ready, the script will print a web address for you to open in your browser.

## Create Your Clinic

1. Open your web browser and go to `http://localhost:8080/signup`
2. Enter your clinic name and choose a username and password (your choice — nothing is pre-filled)
3. Submit the form
4. You'll see a clinic code — write it down or remember it
5. Go to `http://localhost:8080/login`
6. Use the clinic code from step 4, plus the username and password you just created, to log in

## Day-to-Day Use

| Command | What It Does | Your Data |
|---------|--------------|-----------|
| `.\start.ps1` | Starts the application | Kept — nothing erased |
| `.\stop.ps1` | Stops the application | Kept — safe to close your computer after |
| `.\reset.ps1` | Erases everything and starts fresh | **DELETED** — the script asks for confirmation first |

**Important:** Simply restarting your computer, or running `.\stop.ps1` followed by `.\start.ps1`, will never delete your clinic's data.

## Changing the Port

If something else on your computer is already using port 8080:

1. Open `.env` in Notepad
2. Find the line `APP_PORT=8080`
3. Change `8080` to a different number (for example, `8081`)
4. Save the file
5. In PowerShell, run: `.\stop.ps1`
6. Then run: `.\start.ps1`

## If Something Goes Wrong

| Problem | What to Try |
|---------|------------|
| "Docker is not installed" message | Install Docker Desktop from https://www.docker.com/products/docker-desktop/, restart your computer, and try again |
| Nothing happens or a security warning appears | See the PowerShell execution policy fix in the "First Run" section above |
| "port is already allocated" or can't reach the website | Another program is using port 8080; follow the "Changing the Port" section to use a different port |
| Page never loads after `.\start.ps1` finishes | Open PowerShell in this folder and run: `docker compose logs --tail 50 app` — this will show error messages that might explain the problem; if you're unsure what they mean, save the output and ask whoever gave you this package |
| Want to check what's running | Open PowerShell in this folder and run: `docker compose ps` |

## Where Is My Data Stored, and Is It Backed Up?

Your clinic data lives inside Docker's storage on your computer (not in this folder), so it survives if you replace this folder's files with an updated version. **However, there is no automatic backup.** If you need backup copies, that's a separate conversation with whoever gave you this package.

Running `.\reset.ps1` or uninstalling Docker Desktop and deleting its data will permanently erase your clinic's information.

## A Note on Security

The login credentials and configuration in this package are set up for a single computer running locally only (`localhost`). **Do not expose this application to a network or the public internet.** Do not port-forward it. It is not secure for public access.

## Getting an Updated Version Later

If you receive a new folder from whoever gave you this package:

1. Stop the current application: `.\stop.ps1`
2. Replace this folder's contents with the new files
3. Run: `.\start.ps1`

Your existing clinic data will still be there — it's stored separately by Docker and won't be affected by updating the package files.
