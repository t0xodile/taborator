package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.awt.Color;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;

public class TaboratorSettings {
    private final MontoyaApi api;
    private final Gson gson;
    private final PersistedObject extensionData;
    private final Preferences preferences;

    public TaboratorSettings(MontoyaApi api) {
        this.api = api;
        this.gson = new Gson();
        this.extensionData = api.persistence().extensionData();
        this.preferences = api.persistence().preferences();
    }

    public void saveSettings(int unread, int rowNumber, 
                           HashMap<Integer, HashMap<String, String>> interactionHistory,
                           HashMap<String, HashMap<String, String>> originalRequests,
                           HashMap<String, String> originalResponses,
                           ArrayList<Integer> readRows,
                           HashMap<Integer, String> comments,
                           HashMap<Integer, Color> colours,
                           HashMap<Integer, Color> textColours) {
        try {
            // Save to extension data (project-specific)
            extensionData.setInteger("unread", unread);
            extensionData.setInteger("rowNumber", rowNumber);
            
            // Convert complex objects to JSON and save
            extensionData.setString("interactionHistory", gson.toJson(interactionHistory));
            extensionData.setString("originalRequests", gson.toJson(originalRequests));
            extensionData.setString("originalResponses", gson.toJson(originalResponses));
            extensionData.setString("readRows", gson.toJson(readRows));
            extensionData.setString("comments", gson.toJson(comments));
            
            // Colors need special handling since they're not JSON serializable by default
            HashMap<Integer, String> colorStrings = new HashMap<>();
            for (HashMap.Entry<Integer, Color> entry : colours.entrySet()) {
                colorStrings.put(entry.getKey(), String.format("#%06x", entry.getValue().getRGB() & 0xFFFFFF));
            }
            extensionData.setString("colours", gson.toJson(colorStrings));
            
            HashMap<Integer, String> textColorStrings = new HashMap<>();
            for (HashMap.Entry<Integer, Color> entry : textColours.entrySet()) {
                textColorStrings.put(entry.getKey(), String.format("#%06x", entry.getValue().getRGB() & 0xFFFFFF));
            }
            extensionData.setString("textColours", gson.toJson(textColorStrings));
        } catch (Exception e) {
            System.err.println("Error saving Taborator settings: " + e.getMessage());
        }
    }

    public int getUnread() {
        return extensionData.getInteger("unread");
    }

    public int getRowNumber() {
        return extensionData.getInteger("rowNumber");
    }

    public HashMap<Integer, HashMap<String, String>> getInteractionHistory() {
        String json = extensionData.getString("interactionHistory");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<Integer, HashMap<String, String>>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public HashMap<String, HashMap<String, String>> getOriginalRequests() {
        String json = extensionData.getString("originalRequests");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<String, HashMap<String, String>>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public HashMap<String, String> getOriginalResponses() {
        String json = extensionData.getString("originalResponses");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<String, String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public ArrayList<Integer> getReadRows() {
        String json = extensionData.getString("readRows");
        if (json == null || json.isEmpty()) return new ArrayList<>();
        
        Type type = new TypeToken<ArrayList<Integer>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public HashMap<Integer, String> getComments() {
        String json = extensionData.getString("comments");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<Integer, String>>(){}.getType();
        return gson.fromJson(json, type);
    }

    public HashMap<Integer, Color> getColours() {
        String json = extensionData.getString("colours");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<Integer, String>>(){}.getType();
        HashMap<Integer, String> colorStrings = gson.fromJson(json, type);
        
        HashMap<Integer, Color> colors = new HashMap<>();
        for (HashMap.Entry<Integer, String> entry : colorStrings.entrySet()) {
            try {
                Color color = Color.decode(entry.getValue());
                colors.put(entry.getKey(), color);
            } catch (NumberFormatException e) {
                // Skip invalid colors
            }
        }
        return colors;
    }

    public HashMap<Integer, Color> getTextColours() {
        String json = extensionData.getString("textColours");
        if (json == null || json.isEmpty()) return new HashMap<>();
        
        Type type = new TypeToken<HashMap<Integer, String>>(){}.getType();
        HashMap<Integer, String> colorStrings = gson.fromJson(json, type);
        
        HashMap<Integer, Color> colors = new HashMap<>();
        for (HashMap.Entry<Integer, String> entry : colorStrings.entrySet()) {
            try {
                Color color = Color.decode(entry.getValue());
                colors.put(entry.getKey(), color);
            } catch (NumberFormatException e) {
                // Skip invalid colors
            }
        }
        return colors;
    }
}