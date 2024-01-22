package org.crystaltine.mde;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class MDE extends JavaPlugin implements Listener {

    private final String maxHorizLine = ChatColor.AQUA + "+---------------------------------------------------+";
    // Max hyphens (53) that fit in minecraft chat on one line
    // Replaced the edge hyphens with +

    private final String CHAT_PREFIX_ERROR = ChatColor.LIGHT_PURPLE + "[MDE: " + ChatColor.RED + "ERROR" + ChatColor.LIGHT_PURPLE + "] " + ChatColor.RESET;
    private final String CHAT_PREFIX_WARN = ChatColor.LIGHT_PURPLE + "[MDE: " + ChatColor.GOLD + "WARN" + ChatColor.LIGHT_PURPLE + "] " + ChatColor.RESET;
    private final String CHAT_PREFIX_INFO = ChatColor.LIGHT_PURPLE + "[MDE: " + ChatColor.DARK_AQUA + "INFO" + ChatColor.LIGHT_PURPLE + "] " + ChatColor.RESET;
    private final String CHAT_PREFIX_SUCCESS = ChatColor.LIGHT_PURPLE + "[MDE: " + ChatColor.GREEN + "SUCCESS" + ChatColor.LIGHT_PURPLE + "] " + ChatColor.RESET;

    // Keeps track of which files are open and who has them open
    private HashMap<Player, String> currentlyOpenFiles = new HashMap<>();

    // Contains the lines that all players have currently submitted to the server. Write the lines and remove player when they close&save the file.

    // Format:
    // {Player -> List of lines in their currently open file}
    private HashMap<Player, ArrayList<String>> currentBuffers = new HashMap<>();

    // Load from config.yml in onEnable()
    // Contains what to do with each file type
    // Format:
    // {File extension -> Command to run}
    // Defaults:
    // {
    //      cpp -> g++ %1$s.cpp -o %1$s.exe && .\%1$s.exe,
    //      c -> gcc %1$s.c -o %1$s.exe && .\%1$s.exe,
    //      py -> python %1$s.py
    //      java -> javac %1$s.java && java %1$s
    //      exe -> .\%1$s.exe
    // }
    private HashMap<String, String> execConfig = new HashMap<>();


    public ArrayList<String> runFile(String targetFileName) throws IOException {
        // targetFileName should contain the extension
        // Files are stored in mde/files

        // Get the command to run from the config
        // File extension is determined by the characters after the last period (.)
        String exec = execConfig.get(targetFileName.split("\\.")[targetFileName.split("\\.").length - 1]);

        if (exec == null) {
            throw new IllegalArgumentException("File extension not supported");
        }

        exec = "cmd /c " + exec; // add cmd /c to the front of the command to run it in the command prompt

        String targetFilePath = "plugins\\mde\\files\\" + targetFileName; // includes extension
        String targetFilePathNoExt = targetFilePath.substring(0, targetFilePath.lastIndexOf('.'));

        ProcessBuilder runner = new ProcessBuilder(String.format(exec, targetFilePathNoExt).split(" "));
        System.out.println("Running command with following tokens: " + Arrays.toString(String.format(exec, targetFilePathNoExt).split(" ")));

        // Redirect any errors to the output stream so that we only have to read one stream
        runner.redirectErrorStream(true);

        Process outputReceiver = runner.start();

        // Get the output from the process
        BufferedReader outputReader = new BufferedReader(new InputStreamReader(outputReceiver.getInputStream()));

        String line;
        ArrayList<String> lines = new ArrayList<>();
        while ((line = outputReader.readLine()) != null) {
            lines.add(line);
        }
        return lines; // might be runtime errors or just output
    }

    private void writeBufferToFile(ArrayList<String> newFileContent, String fileName) throws IOException {

        System.out.println("Writing to a file. See below for contents");
        for (String s : newFileContent) {
            System.out.println(s);
        }

        String targetFilePath = "plugins\\mde\\files\\" + fileName;
        Path filePath = Paths.get(targetFilePath);

        Files.write(filePath, newFileContent); // this will overwrite the file
    }

    private int indexOfCodeStart(String rawMessageContent, char endDirectiveChar) {
        // Message will start with something like $#15>.
        // If users put ONE space after > or +, then remove it
        // If there are multiple spaces (likely a tab), then leave ALL of them

        int endIndex = rawMessageContent.indexOf(endDirectiveChar);

        if (endIndex == -1) { // although this should never happen
            return -1;
        }

        if (rawMessageContent.length() <= endIndex + 1) { // nothing after the directive char
            return -1;
        }

        if (rawMessageContent.charAt(endIndex + 1) == ' ') {
            if (rawMessageContent.charAt(endIndex + 2) == ' ') { // more spaces, so leave them (code starts at first space)
                return endIndex + 1;
            } else { // trim the space, so code starts at the next char
                return endIndex + 2;
            }
        }
        return endIndex + 1; // no spaces, so code starts at the next char
    }

    @EventHandler
    private void onMessage(AsyncPlayerChatEvent e) {
        if (checkForWriteLinePrefix(e.getMessage())) {

            int startIndex = indexOfCodeStart(e.getMessage(), '>');
            String relevantMessageContent = startIndex == -1? "" : e.getMessage().substring(startIndex);

            int lineNum = Integer.parseInt(e.getMessage().split(">")[0].split("#")[1]);
            if (lineNum < 1) {
                e.getPlayer().sendMessage(CHAT_PREFIX_WARN + "Line number must be positive. No changes made.");
                e.setCancelled(true);
                return;
            }

            Player player = e.getPlayer();

            // If the sender has a file open, write the message to the file they have open
            if (currentlyOpenFiles.containsKey(player)) {

                // Add message to the buffer
                ArrayList<String> currentBuffer = currentBuffers.get(player);

                if (lineNum > currentBuffer.size()) {
                    // Add empty lines to the buffer until the line number is valid
                    for (int i = currentBuffer.size(); i < lineNum-1; i++) {
                        currentBuffer.add("");
                    }
                    currentBuffer.add(relevantMessageContent);
                } else {
                    currentBuffer.set(lineNum-1, relevantMessageContent); // Line numbers are 1-indexed, but the buffer is 0-indexed
                }

                currentBuffers.put(player, currentBuffer);

                // send confirmation message
                player.sendMessage(ChatColor.YELLOW + "SET " + ChatColor.AQUA + "" + lineNum + " | " + ChatColor.RESET + relevantMessageContent);
                System.out.println("SET " + lineNum + " | " + relevantMessageContent + " by " + player.getName() + " to " + currentlyOpenFiles.get(player));

            } else { // Player doesn't have a file open
                player.sendMessage(CHAT_PREFIX_ERROR + "Can't write line: You don't have a file open.");
            }

            e.setCancelled(true); // Hide all messages with the prefix, even if they are not valid (don't have a file open)
        }
        else if (checkForInsertLinePrefix(e.getMessage())) {

            int startIndex = indexOfCodeStart(e.getMessage(), '+');
            String relevantMessageContent = startIndex == -1? "" : e.getMessage().substring(startIndex);

            int lineNum = Integer.parseInt(e.getMessage().split("\\+")[0].split("#")[1]);
            if (lineNum < 1) {
                e.getPlayer().sendMessage(CHAT_PREFIX_WARN + "Line number must be positive. No changes made.");
                e.setCancelled(true);
                return;
            }

            Player player = e.getPlayer();

            // If the sender has a file open, write the message to the file they have open
            if (currentlyOpenFiles.containsKey(player)) {
                // Add message to the buffer
                ArrayList<String> currentBuffer = currentBuffers.get(player);

                if (lineNum > currentBuffer.size()) {
                    // Add empty lines to the buffer until the line number is valid
                    for (int i = currentBuffer.size(); i < lineNum - 1; i++) {
                        currentBuffer.add("");
                    }
                    currentBuffer.add(relevantMessageContent);
                } else {
                    currentBuffer.add(lineNum-1, relevantMessageContent); // Line numbers are 1-indexed, but the buffer is 0-indexed
                }

                currentBuffers.put(player, currentBuffer);

                // send confirmation message
                player.sendMessage(ChatColor.GREEN + "INS " + ChatColor.AQUA + "" + lineNum + " | " + ChatColor.RESET + relevantMessageContent);
                System.out.println("INS " + lineNum + " | " + relevantMessageContent + " by " + player.getName() + " to " + currentlyOpenFiles.get(player));

            } else { // Player doesn't have a file open
                player.sendMessage(CHAT_PREFIX_ERROR + "Can't insert line: You don't have a file open.");
            }

            e.setCancelled(true); // Hide all messages with the prefix, even if they are not valid (don't have a file open)
        }
        else if (checkForDeleteLinePrefix(e.getMessage())) {
            // Don't mind anything after the directive char, just delete the line (everything shifts down)
            // If line out of bounds, warn and do nothing
            Player player = e.getPlayer();

            if (currentlyOpenFiles.containsKey(player)) {

                int lineNum = Integer.parseInt(e.getMessage().split("\\-")[0].split("#")[1]);
                if (lineNum < 1) {
                    e.getPlayer().sendMessage(CHAT_PREFIX_WARN + "Line number must be positive. No changes made.");
                    e.setCancelled(true);
                    return;
                } else if (lineNum > currentBuffers.get(e.getPlayer()).size()) {
                    e.getPlayer().sendMessage(CHAT_PREFIX_WARN + "Line number out of bounds. No changes made.");
                    e.setCancelled(true);
                    return;
                }

                // remove index lineNum-1 from the buffer
                ArrayList<String> currentBuffer = currentBuffers.get(e.getPlayer());
                String originalContent = currentBuffer.remove(lineNum - 1);
                currentBuffers.put(e.getPlayer(), currentBuffer);
                e.setCancelled(true);

                // send confirmation message
                player.sendMessage(ChatColor.RED + "DEL " + ChatColor.AQUA + ChatColor.STRIKETHROUGH + "" + lineNum + ChatColor.RESET + ChatColor.AQUA + " | " + ChatColor.RESET + ChatColor.STRIKETHROUGH + originalContent);
                System.out.println("DEL " + lineNum + " | " + originalContent + " by " + player.getName() + " from " + currentlyOpenFiles.get(player));
            } else {
                player.sendMessage(CHAT_PREFIX_ERROR + "Can't delete line: You don't have a file open.");
            }
            e.setCancelled(true);
        }
    }

    private boolean checkForWriteLinePrefix(String message) {
        // $#(Line number)>
        String pattern = "^\\$#\\d+>.*";
        return message.matches(pattern);
    }
    private boolean checkForInsertLinePrefix(String message) {
        // $#(Line number)+
        String pattern = "^\\$#\\d+\\+.*";
        return message.matches(pattern);
    }
    private boolean checkForDeleteLinePrefix(String message) {
        // $#(Line number)-
        String pattern = "^\\$#\\d+\\-.*";
        return message.matches(pattern);
    }

    private void sendFileContentsToPlayer(Player player, String targetFileName) throws IOException {
        // Send the contents of the file to the player

        String targetFilePath = "plugins\\mde\\files\\" + targetFileName;
        Path filePath = Paths.get(targetFilePath);
        List<String> fileLines = Files.readAllLines(filePath);

        // Get max num of digits needed in the line numbers
        // We need to space out line nums with less digits so all the code is left-aligned
        int maxDigits = (int) Math.log10(fileLines.size()) + 1;

        player.sendMessage("");
        player.sendMessage("File contents: [ " + ChatColor.YELLOW  + targetFileName + ChatColor.RESET + " ]");
        player.sendMessage(maxHorizLine);

        for (int i = 0; i < fileLines.size(); i++) {
            int lineNumDigits = (int) Math.log10(i+1) + 1;
            String padding = " ".repeat(maxDigits - lineNumDigits);
            player.sendMessage(ChatColor.AQUA + "" + (i+1) + padding + " | " + ChatColor.RESET + fileLines.get(i));
        }
        if (fileLines.size() == 0) player.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "(Empty file)");

        player.sendMessage(maxHorizLine);
    }

    @Override
    public void onEnable() {
        System.out.println("mde enabled - good luck xd");

        // register events
        getServer().getPluginManager().registerEvents(this, this);

        // Load config for execConfig from mde_execConfig.yml
        // ^ TODO
        // Just use default for now
        execConfig = new HashMap<>();
        // cpp -> g++ %1$s.cpp -o %1$s.exe && .\%1$s.exe,
        // c -> gcc %1$s.c -o %1$s.exe && .\%1$s.exe,
        // py -> python %1$s.py
        // java -> javac %1$s.java && java %1$s
        execConfig.put("cpp", "g++ %1$s.cpp -o %1$s.exe && .\\%1$s.exe");
        execConfig.put("c", "gcc %1$s.c -o %1$s.exe && .\\%1$s.exe");
        execConfig.put("py", "python %1$s.py");
        execConfig.put("java", "javac %1$s.java && java %1$s");
        execConfig.put("exe", ".\\%1$s.exe");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Ignore non-player senders
        if (!(sender instanceof Player)) {
            sender.sendMessage(CHAT_PREFIX_ERROR + "Only players can use this command.");
            return true;
        }

        if (label.equals("mde")) { // Main command
            if (args.length == 0 || args[0].equals("help")) {
                // Help command
                sender.sendMessage("");
                sender.sendMessage(maxHorizLine);
                sender.sendMessage("" + ChatColor.UNDERLINE + ChatColor.LIGHT_PURPLE + "MDE Help");
                sender.sendMessage("MDE allows players to write and execute code in-game... if for some weird reason you would ever want to.");
                sender.sendMessage("");
                sender.sendMessage("" + ChatColor.UNDERLINE + ChatColor.LIGHT_PURPLE + "Supported Languages:");
                sender.sendMessage("C++, C, Java, Python"); //depending on server, TODO: add config to specify CLI commands for each language so they can be run
                sender.sendMessage("");
                sender.sendMessage("" + ChatColor.UNDERLINE + ChatColor.LIGHT_PURPLE + "Commands:");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde help" + ChatColor.GRAY + " Displays this help message.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde open " + ChatColor.AQUA + "<filename>" + ChatColor.GRAY + " Opens the specified file or creates it (if nonexistent)");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde close " + ChatColor.AQUA + "[dontsave]" + ChatColor.GRAY + " Closes the player's currently open file. Saves unless " + ChatColor.AQUA + "dontsave" + ChatColor.RESET + " is specified.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde run " + ChatColor.AQUA + "<filename>" + ChatColor.GRAY + " Executes (or compiles, then executes) specified file if it is written in (has a matching file extension to) a supported language.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde rename " + ChatColor.AQUA + "<newname>" + ChatColor.GRAY + " Renames the player's currently open file to the specified name. Must have a file open.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde list" + ChatColor.GRAY + ": Lists all files in the player's files directory.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde view " + ChatColor.AQUA + "[live|saved]" + ChatColor.GRAY + " Sends the current contents of the open file in chat. If the optional argument " + ChatColor.AQUA + "saved" + ChatColor.RESET + " is specified, the contents will be the last saved version of the file. Otherwise (no arguments are given or " + ChatColor.AQUA + "live" + ChatColor.RESET + " is specified) the contents will be a preview of the file if current changes were to be applied.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde peek " + ChatColor.AQUA + "<filename>" + ChatColor.GRAY + " Sends the currently saved contents of the specified file in chat, without the need for it to be opened.");
                sender.sendMessage(ChatColor.DARK_AQUA + "/mde delete" + ChatColor.GRAY + ": Deletes the player's currently open file. Must have a file open.");
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.ITALIC + "Note that filenames should include file extensions.");
                sender.sendMessage("");
                sender.sendMessage("" + ChatColor.UNDERLINE + ChatColor.LIGHT_PURPLE + "Writing Code:");
                sender.sendMessage("When a file is open, you may type code into the chat.");
                sender.sendMessage("You must specify an operation and a line number using the prefixes below:");
                sender.sendMessage(ChatColor.DARK_AQUA + "$#" + ChatColor.AQUA + "Line number" + ChatColor.DARK_AQUA + "> " + ChatColor.AQUA + "code" + ChatColor.GRAY + " Overwrite a line of code at the line number, inserting a blank gap if it is outside the current file length. Line# must be positive.");
                sender.sendMessage(ChatColor.DARK_AQUA + "$#" + ChatColor.AQUA + "Line number" + ChatColor.DARK_AQUA + "+ " + ChatColor.AQUA + "code" + ChatColor.GRAY + " Insert a line of code at the line number, shifting any lower code down one line, and inserting a blank gap if it is outside the current file length. Line# must be positive.");
                sender.sendMessage(ChatColor.DARK_AQUA + "$#" + ChatColor.AQUA + "Line number" + ChatColor.DARK_AQUA + "-" + ChatColor.GRAY + " Delete a line at the line number, shifting any lower code up one line. Any other characters after this prefix are ignored. Does nothing if Line# is out of bounds or not positive");
                sender.sendMessage(maxHorizLine);
                return true;
            }
            if (args[0].equals("open")) {
                if (args.length == 1) { // No file specified
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Please specify a file name.");
                    return true;
                }
                if (currentlyOpenFiles.containsKey(sender)) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + ChatColor.YELLOW + currentlyOpenFiles.get((Player) sender) + ChatColor.RESET + " is already open. Please close it first.");
                    return true;
                }

                String targetFileName = args[1];

                // Filenames can only be letters, numbers, underscores, and periods
                if (!targetFileName.matches("[a-zA-Z0-9_.]+")) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "File names can only contain letters, numbers, underscores, and periods. Please try again.");
                    return true;
                }

                File newFile = new File("plugins\\mde\\files\\" + targetFileName);
                boolean createdNewFile = false;

                try {
                    createdNewFile = newFile.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.out.println("Error occurred while creating/opening file " + targetFileName + ".");
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while creating/opening file " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + ".");
                    return true;
                }

                if (createdNewFile) { // New file will have already been created
                    currentBuffers.put((Player) sender, new ArrayList<String>()); // file is empty so buffer is empty
                    sender.sendMessage("");
                    sender.sendMessage(CHAT_PREFIX_SUCCESS + "File " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + " created and opened.");
                    sender.sendMessage("");
                } else {
                    try {
                        sendFileContentsToPlayer((Player) sender, targetFileName);

                        ArrayList<String> fileLines = new ArrayList<String>(Files.readAllLines(Paths.get("plugins\\mde\\files\\" + targetFileName)));
                        currentBuffers.put((Player) sender, fileLines);

                        sender.sendMessage(CHAT_PREFIX_SUCCESS + "Successfully opened " + ChatColor.YELLOW + targetFileName);
                        sender.sendMessage("");
                    } catch (IOException e) {
                        sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while opening file " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + ".");
                        e.printStackTrace();
                        return true;
                    }
                }

                // Create a new buffer for the file
                currentlyOpenFiles.put((Player) sender, targetFileName);

                return true;
            }
            if (args[0].equals("close")) {
                if (currentlyOpenFiles.containsKey(sender)) {

                    String closedFileName = currentlyOpenFiles.remove(sender);
                    ArrayList<String> currentBuffer = currentBuffers.remove(sender);

                    if (args.length > 1 && args[1].equals("dontsave")) {
                        // Discard the buffer (don't save)
                        sender.sendMessage(CHAT_PREFIX_SUCCESS + "File " + ChatColor.YELLOW + closedFileName + ChatColor.RESET + " closed without saving.");
                        return true;
                    } else {

                        // Write the buffer to the file (save)
                        try {
                            writeBufferToFile(currentBuffer, closedFileName);
                        } catch (IOException e) {
                            sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while saving file " + ChatColor.YELLOW + closedFileName + ChatColor.RESET + ". Don't worry, your changes have not been lost. Contact admins for help.");
                            e.printStackTrace();
                            System.out.println(">>>>> ERROR OCCURRED WHILE SAVING FILE " + closedFileName + ", BUFFER WILL BE PRINTED BELOW <<<<<");
                            System.out.println(currentBuffer.toString());
                            return true;
                        }

                        try {
                            sendFileContentsToPlayer((Player) sender, closedFileName);
                            sender.sendMessage(CHAT_PREFIX_SUCCESS + "File " + ChatColor.YELLOW + closedFileName + ChatColor.RESET + " saved and closed.");
                        } catch (IOException e) {
                            sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while reading new contents of file " + ChatColor.YELLOW + closedFileName + ChatColor.RESET + ", although the file was saved successfully. Contact admins for help.");
                            e.printStackTrace();
                            System.out.println(">>>>> ERROR OCCURRED WHILE READING NEW CONTENTS OF FILE " + closedFileName + ", BUT CONTENTS WERE SAVED SUCCESSFULLY. SEE FILE.");
                            return true;
                        }
                        return true;
                    }

                } else {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Can't close file: You don't have a file open.");
                }
                return true;
            }
            if (args[0].equals("run")) {

                if (args.length == 1) { // No file specified
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Please specify an existing file name");
                    return true;
                }

                String targetFileName = args[1];

                // Check if file exists
                File targetFile = new File("plugins\\mde\\files\\" + targetFileName);
                if (!targetFile.exists()) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "File " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + " does not exist.");
                    return true;
                }

                try {
                    ArrayList<String> result = runFile(targetFileName);
                    Player player = (Player) sender;

                    player.sendMessage("");
                    player.sendMessage("Output: [ " + ChatColor.YELLOW  + targetFileName + ChatColor.RESET + " ]");
                    player.sendMessage(maxHorizLine);

                    for (String s : result) player.sendMessage(s);
                    if (result.size() == 0) player.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "(No output/error lines)");

                    player.sendMessage(maxHorizLine);

                } catch (IOException e) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while compiling/running file " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + ".");
                    e.printStackTrace();
                    System.out.println(">>>>> IO ERROR OCCURRED IN RUNFILE FOR " + targetFileName + " <<<<<");
                    return true;
                } catch (IllegalArgumentException e) {
                    // The file extension isn't supported, suggest they change filename or talk to admins
                    sender.sendMessage(CHAT_PREFIX_ERROR + "File " + ChatColor.YELLOW + targetFileName + ChatColor.RESET + " is not a supported file type. Please rename the file or ask the admins to configure MDE.");
                    return true;
                }
                return true;
            }
            if (args[0].equals("rename")) {
                if (args.length < 2) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Please specify a new file name. Make sure to include the extension.");
                    return true;
                }

                // rename the currently open file
                Player player = (Player) sender;

                if (!currentlyOpenFiles.containsKey(player)) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Can't rename file: You don't have a file open.");
                    return true;
                }

                File oldFile = new File("plugins\\mde\\files\\" + currentlyOpenFiles.get(player));
                String oldName = currentlyOpenFiles.get(player);
                boolean success = oldFile.renameTo(new File("plugins\\mde\\files\\" + args[2]));

                if (success) {
                    currentlyOpenFiles.put(player, args[1]); // update the currently open file so when they close it, it saves to the new file
                }

                String message = success?
                        CHAT_PREFIX_SUCCESS + "File " + ChatColor.YELLOW + oldName + ChatColor.RESET + " renamed to " + ChatColor.YELLOW + args[1] + ChatColor.RESET + "." :
                        CHAT_PREFIX_ERROR + "Couldn't rename file " + ChatColor.YELLOW + oldName + ChatColor.RESET + " to " + ChatColor.YELLOW + args[1] + ChatColor.RESET + ". Make sure it doesn't already exist.";
                sender.sendMessage(message);

                return true;
            }
            if (args[0].equals("list")) {
                // List all files in the files directory
                File filesDir = new File("plugins\\mde\\files");
                File[] files = filesDir.listFiles();

                sender.sendMessage("");
                sender.sendMessage(String.format("All files: (%d results)", files.length));
                sender.sendMessage(maxHorizLine);

                for (File f : files) sender.sendMessage(ChatColor.YELLOW + f.getName());
                if (files.length == 0) sender.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "(No files yet)");

                sender.sendMessage(maxHorizLine);
                return true;
            }
            if (args[0].equals("view")) {
                // Args: <saved|live>
                // Saved - view the saved, not including any changes made since the file was opened
                // Live - previews the file with the changes applied, but doesn't save them yet.
                // (Live is default)

                // Must have a file open
                if (!currentlyOpenFiles.containsKey(sender)) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Can't view file contents: You don't have a file open.");
                    return true;
                }

                if (args.length < 2 || args[1].equals("live")) {

                    // Send the contents of the live buffer to the player
                    Player player = (Player) sender;
                    ArrayList<String> fileLines = currentBuffers.get((Player) sender);
                    String currentlyOpenFilename = currentlyOpenFiles.get(player);

                    int maxDigits = (int) Math.log10(fileLines.size()) + 1;

                    player.sendMessage("");
                    player.sendMessage("Preview changes: [ " + ChatColor.YELLOW  + currentlyOpenFilename + ChatColor.RESET + " ]");
                    player.sendMessage(maxHorizLine);

                    for (int i = 0; i < fileLines.size(); i++) {
                        int lineNumDigits = (int) Math.log10(i+1) + 1;
                        String padding = " ".repeat(maxDigits - lineNumDigits);
                        player.sendMessage(ChatColor.AQUA + "" + (i+1) + padding + " | " + ChatColor.RESET + fileLines.get(i));
                    }
                    if (fileLines.size() == 0) player.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "(Empty file)");

                    player.sendMessage(maxHorizLine);
                }
                else if (args[1].equals("saved")) {
                    // Just use the sendFileContentsToPlayer method
                    try {
                        sendFileContentsToPlayer((Player) sender, currentlyOpenFiles.get(sender));
                    } catch (IOException e) {
                        sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while reading file " + ChatColor.YELLOW + currentlyOpenFiles.get(sender) + ChatColor.RESET + ". Contact admins for help.");
                        e.printStackTrace();
                        System.out.println(">>>>> ERROR OCCURRED WHILE READING FILE " + currentlyOpenFiles.get(sender) + ", BUFFER WILL BE PRINTED BELOW <<<<<");
                        System.out.println(currentBuffers.get(sender).toString());
                        return true;
                    }
                }
                return true;
            }
            if (args[0].equals("peek")) {
                // Peek at a file's saved contents. Does not require the file to be open.
                // Must specify a file name
                if (args.length < 2) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Please specify an existing file name. Make sure to include the extension.");
                    return true;
                }

                // Check if file exists
                File targetFile = new File("plugins\\mde\\files\\" + args[1]);
                if (!targetFile.exists()) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "File " + ChatColor.YELLOW + args[1] + ChatColor.RESET + " does not exist. Make sure the extension is correct.");
                    return true;
                }

                // Send the contents of the file to the player
                try {
                    sendFileContentsToPlayer((Player) sender, args[1]);
                } catch (IOException e) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Error occurred while reading file " + ChatColor.YELLOW + args[1] + ChatColor.RESET + ". Contact admins for help.");
                    e.printStackTrace();
                    System.out.println(">>>>> ERROR OCCURRED WHILE READING FILE WITH /PEEK" + args[1] + ", BUFFER WILL BE PRINTED BELOW <<<<<");
                    System.out.println(currentBuffers.get(sender).toString());
                    return true;
                }
                return true;
            }
            if (args[0].equals("delete")) {
                // Close the currently opened file and delete it
                // Basically just clear the buffer, then delete the file on the server
                Player player = (Player) sender;
                String fileName = currentlyOpenFiles.remove(player);
                currentBuffers.remove(player);

                if (fileName == null) {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Can't delete file: You don't have a file open.");
                    return true;
                }

                File file = new File("plugins\\mde\\files\\" + fileName);
                boolean success = file.delete();

                if (success) {
                    sender.sendMessage(CHAT_PREFIX_SUCCESS + "File " + ChatColor.YELLOW + fileName + ChatColor.RESET + " deleted.");
                } else {
                    sender.sendMessage(CHAT_PREFIX_ERROR + "Couldn't delete file " + ChatColor.YELLOW + fileName + ChatColor.RESET + ". Contact admins for help.");
                    System.out.println(">>>>> ERROR OCCURRED WHILE DELETING FILE " + fileName);
                }

            }
        }

        return false;
    }
}
