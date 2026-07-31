# Video Knowledge Sorter - Detailed Functional Specification

This document provides an in-depth breakdown of every page and major component within the Video Knowledge Sorter app. It details the intended purpose, exact functions, and the standard user flow for each area to inform the UX/UI redesign process.

---

## 1. Global App Context & Navigation
**Overall App Purpose:** To scan, organize, and analyze local device videos (and potentially screenshots) using local LLM models (like Ollama) or cloud APIs (like Gemini) combined with frame OCR. It helps users make sense of their media clutter, intelligently rename files, and easily delete junk.

### 1.1 Sidebar / Drawer Navigation
*   **Purpose:** The primary method for moving between the main logical sections of the app.
*   **Functions:** Links to `Dashboard`, `Storage Folders`, `Active Backlog`, and `Archive (Processed)`.
*   **User Flow:** The user opens the sidebar (or views it fixed on tablet/landscape) to switch their current context from managing folders to triaging the backlog.

### 1.2 Top App Bar
*   **Purpose:** Global actions available from anywhere in the app.
*   **Functions:** 
    *   **Theme Toggle:** Instantly switches the app between Light and Dark mode UI palettes.
    *   **Computer Sync:** Initiates a transfer of processed metadata/data to a connected PC. Displays a pulsing animation when active and a progress bar during transfer.
*   **User Flow:** A user preferring dark mode taps the sun/moon icon. After finishing a session of organizing videos, the user taps the Computer Sync icon to back up their data to their desktop.

---

## 2. Dashboard (Main View)
**Purpose:** The central command hub and status monitor for the application. It provides at-a-glance metrics of the app's state and is the starting point for bulk actions.

**Key Functions & What They Do:**
*   **Permissions Management:** On first launch, prompts the user to grant storage/video permissions. It handles states like "Full access", "Limited access" (Android 14+ specific photo picker), and "Denied".
*   **Media Statistics:** Displays counts such as "Videos Found" or "Items in Queue".
*   **Batch Processing Controls:** Controls for automating the LLM queue. Includes "Start Batch", "Pause Batch", and "Process Next". A visual track card shows live progress.

**How the User Uses It:**
Upon opening the app, the user checks the Dashboard to see if there are videos waiting to be processed. If the queue is populated, they tap "Start Batch" and leave the phone running while the app iterates through the videos, applying AI analysis to each one. They can pause this at any time.

---

## 3. Storage Folders
**Purpose:** To give the user control over *where* the app looks for media, preventing it from scanning the entire device if not desired.

**Key Functions & What They Do:**
*   **Register Target Watch Folder:** Opens the native Android system folder picker. The user selects a specific directory (e.g., "Screen Recordings") and grants the app persistent URI permission to read it.
*   **Folder Auto-detection:** Lists the registered folders and any auto-detected media folders.
*   **Add Selected to Backlog:** Allows the user to select specific folders via checkboxes and bulk-add all their contents to the processing queue.

**How the User Uses It:**
A user who only wants to analyze their screen recordings navigates here, taps "Register Path", and selects their "Movies/ScreenRecords" folder. They then check the box next to that folder and click "Add Selected to Backlog" so the AI only processes those specific files.

---

## 4. Settings & Configuration (LLM Panel)
**Purpose:** The technical configuration center where the user wires up the "brain" of the app.

**Key Functions & What They Do:**
*   **LLM Provider Selection:** Switch between different AI engines (e.g., Gemini API or a local Ollama instance).
*   **Connection Testing:** Input an API key or Local URL and click "Test Connection Host". A Red/Green status badge indicates if the app can successfully reach the AI.
*   **Model Synchronization:** Fetches the available models from the connected provider and populates a dropdown (e.g., selecting `gemini-1.5-pro` vs `gemini-1.5-flash`).
*   **A.I. System Prompt Editor:** A collapsible text area where advanced users can edit the exact prompt sent to the AI, customizing how the AI categorizes and summarizes the videos.
*   **Auto-Ingestion Setup:** Toggles for background services, allowing the app to silently "Watch Screenshots" or "Watch Screen Recordings" as they are created on the device.

**How the User Uses It:**
During initial setup, the user enters their Gemini API key and clicks "Test". Seeing the green "Connected" badge, they sync models and pick their preferred one. Later, if they want the AI to analyze different things, they return here to tweak the System Prompt.

---

## 5. Active Backlog
**Purpose:** The to-do list. It shows all media items that have been ingested but are waiting for user review or AI processing.

**Key Functions & What They Do:**
*   **List View with Status Indicators:** Displays video thumbnails, titles, sizes, and their current processing status (e.g., "Queued", "Processing").
*   **Filter & Sort:** Dropdowns to narrow down the list (e.g., show only "Queued" items, sorted by "Size").
*   **Smart LLM AI Rename:** A one-click action on an individual list item that uses OCR (Optical Character Recognition) on the video frames to generate a smart, descriptive file name (usually in snake_case format).

**How the User Uses It:**
The user opens the Backlog to triage their videos. They might notice a video with a generic name like `VID_20231024.mp4`, click the "LLM AI" button, and watch it automatically rename to `gameplay_victory_screen.mp4`. They use the list to select videos to dive deeper into.

---

## 6. Details & Editing Panel (Expanded View)
**Purpose:** The granular review and manual editing interface for a single piece of media. This is where the user interacts with the results of the AI's analysis.

**Key Functions & What They Do:**
*   **Metadata Review & Edit:** Displays the AI-generated Category, Description, Action Roadmap (suggested next steps based on the video content), and Manual Transcript. The user can manually type in these fields to correct the AI.
*   **Save Edits:** Persists any manual changes to the local database.
*   **State Management Actions:** Buttons to change the item's lifecycle:
    *   *Archive:* Moves the item to the "Archive (Processed)" view, marking it as completely done.
    *   *Reset Model Data:* Wipes the AI analysis if it was poor, returning the item to the backlog for a retry.
    *   *Mark Trash Candidate:* Tags the file visually as junk, ready for bulk deletion later.
*   **Native OS File Deletion:** Buttons to "Move to Trash" or "Delete Forever". This invokes native Android system prompts to safely delete the physical video file from the device storage.

**How the User Uses It:**
After the AI finishes processing a batch, the user taps a video to review it. The AI thinks it's a "Receipt", but it's actually a "Business Card". The user manually changes the Category field and taps "Save Edits". Realizing they don't need the video anymore because the text is saved, they tap "Delete Forever", confirm the Android system prompt, and free up space on their phone.

---

## 7. Archive (Processed)
**Purpose:** The historical log of completely processed and finalized items.

**Key Functions & What They Do:**
*   **Read-Only/Review List:** Displays all items that have been explicitly marked as "Archived" from the Details Panel.
*   **Filter/Search (Implied):** Allows finding previously processed information without mixing it up with the messy to-do list.

**How the User Uses It:**
Months after processing a bunch of tutorial videos, the user navigates to the Archive to look up the AI-generated summary of a specific video without having to re-watch the media.
