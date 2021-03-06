
package org.bsdata.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.commons.io.FilenameUtils;
import org.bsdata.constants.DataConstants;
import org.bsdata.model.Catalogue;
import org.bsdata.model.DataIndex;
import org.bsdata.model.DataIndexEntry;
import org.bsdata.model.GameSystem;
import org.bsdata.model.Roster;
import org.bsdata.utils.Utils;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.stream.Format;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;


/**
 * Handles creating a BattleScribe repository index from a set of data files.
 * 
 * @author Jonskichov
 */
public class Indexer {
    
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static Persister persister;
    
    /**
     * The SimpleXML persister used to read/write a data index object to/from XML.
     * 
     * @return 
     */
    private static Persister getPersister() {
        if (persister == null) {
            persister = new Persister(new Format(2, XML_DECLARATION));
        }
        return persister;
    }

    /**
     * Writes a data file repository index to an in-memory XML file.
     * 
     * @param dataIndex
     * @return
     * @throws IOException
     * @throws XmlException 
     */
    private byte[] writeDataIndex(DataIndex dataIndex) throws IOException, XmlException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            getPersister().write(dataIndex, outputStream, "UTF-8");
            return outputStream.toByteArray();
        }
        catch (Exception ex) {
            throw new XmlException(ex);
        }
    }
    
    /**
     * Returns a complete data file repository from a set of data files (including the index) from a particular GitHub repository.
     * 
     * 1) A data file repository index file is created from the data files.
     * 2) All data files and the index file are ensured to be compressed.
     * 3) All file names are ensured to be the compressed format.
     * 4) A HashMap of compressed data file name to compressed file data is created.
     * 5) The HashMap is returned so it can be cached and/or served.
     * 
     * @param repositoryName
     * @param baseUrl
     * @param repositoryUrls
     * @param dataFiles
     * @return
     * @throws IOException 
     */
    public HashMap<String, byte[]> createRepositoryData(
            String repositoryName, 
            String baseUrl, 
            List<String> repositoryUrls, 
            HashMap<String, byte[]> dataFiles) throws IOException {
        
        DataIndex dataIndex = createDataIndex(repositoryName, baseUrl, repositoryUrls, dataFiles);
        dataFiles.put(DataConstants.DEFAULT_INDEX_FILE_NAME, writeDataIndex(dataIndex));
        return compressRepositoryData(dataFiles);
    }
    
    /**
     * Returns a new map of fileName to fileData ensuring all file names are compressed names and all data is compressed.
     * When an uncompressed file name is encountered, the associated inputStream is compressed.
     * 
     * @param dataFiles
     * @return
     * @throws IOException 
     */
    private HashMap<String, byte[]> compressRepositoryData(HashMap<String, byte[]> dataFiles) throws IOException {
        HashMap<String, byte[]> compressedDataFiles = new HashMap<>();
        for (String fileName : dataFiles.keySet()) {
            byte[] data = dataFiles.get(fileName);

            if (Utils.isCompressedPath(fileName)) {
                // Data already compressed - just make sure it's a file name (not full path)
                fileName = FilenameUtils.getName(fileName);
                compressedDataFiles.put(fileName, data);
            }
            else {
                // We need to compress the data
                byte[] compressedData = Utils.compressData(fileName, data);
                fileName = Utils.getCompressedFileName(fileName);
                compressedDataFiles.put(fileName, compressedData);
            }
        }
        return compressedDataFiles;
    }

    /**
     * Create a data index from a set of data files.
     * Ensures the resulting data index entries use compressed file names.
     * 
     * @param repositoryName A name for the repo
     * @param baseUrl The part of the URL before "index.bsi"
     * @param repositoryUrls Optional list of repo URLs to include in the index
     * @param dataFiles The data files to make the index from
     * 
     * @return
     * @throws IOException
     * @throws XmlException 
     */
    private DataIndex createDataIndex(
            String repositoryName, 
            String baseUrl, 
            List<String> repositoryUrls, 
            HashMap<String, byte[]> dataFiles)
            throws IOException, XmlException {

        String indexUrl = Utils.checkUrl(baseUrl + "/" + repositoryName + "/" + DataConstants.DEFAULT_INDEX_COMPRESSED_FILE_NAME);
        DataIndex dataIndex = new DataIndex(repositoryName, indexUrl, repositoryUrls);

        for (String fileName : dataFiles.keySet()) {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(dataFiles.get(fileName));
            if (Utils.isCompressedPath(fileName)) {
                // Decompress the stream if we need to so we can read the XML
                inputStream = Utils.decompressStream(inputStream);
            }
            
            // Make sure we use a compressed file names in the index. 
            // This will also ensure it's just the filename, not full path.
            fileName = Utils.getCompressedFileName(fileName);
            
            try {
                // Create a data index entry and add it to our data index
                DataIndexEntry dataIndexEntry;
                
                if (Utils.isGameSytstemPath(fileName)) {
                    GameSystem gameSystem = readGameSystem(inputStream);
                    dataIndexEntry = new DataIndexEntry(fileName, gameSystem);
                }
                else if (Utils.isCataloguePath(fileName)) {
                    Catalogue catalogue = readCatalogue(inputStream);
                    dataIndexEntry = new DataIndexEntry(fileName, catalogue);
                }
                else if (Utils.isRosterPath(fileName)) {
                    Roster roster = readRoster(inputStream);
                    dataIndexEntry = new DataIndexEntry(fileName, roster);
                }
                else {
                    continue;
                }
                
                dataIndex.getDataIndexEntries().add(dataIndexEntry);
            }
            catch (IOException e) {
                // TODO: handle exception
            }
        }
        
        return dataIndex;
    }

    /**
     * Buffers internally
     * 
     * @param inputStream
     * @return
     * @throws XmlException 
     */
    private Catalogue readCatalogue(ByteArrayInputStream inputStream) throws XmlException, IOException {
        final Catalogue catalogue = new Catalogue();
        try {
            SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
            saxParser.parse(inputStream, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if (qName.equalsIgnoreCase(DataConstants.CATALOGUE_TAG)) {
                        catalogue.setId(attributes.getValue(DataConstants.ID_ATTRIBUTE));
                        catalogue.setGameSystemId(attributes.getValue(DataConstants.GAME_SYSTEM_ID_ATTRIBUTE));
                        catalogue.setBattleScribeVersion(attributes.getValue(DataConstants.BATTLESCRIBE_VERSION_ATTRIBUTE));
                        catalogue.setRevision(Integer.parseInt(attributes.getValue(DataConstants.REVISION_ATTRIBUTE)));
                        catalogue.setName(attributes.getValue(DataConstants.NAME_ATTRIBUTE));
                        catalogue.setAuthorName(attributes.getValue(DataConstants.AUTHOR_NAME_ATTRIBUTE));
                        catalogue.setAuthorContact(attributes.getValue(DataConstants.AUTHOR_CONTACT_ATTRIBUTE));
                        catalogue.setAuthorUrl(attributes.getValue(DataConstants.AUTHOR_URL_ATTRIBUTE));
                        throw new SAXParseCompleteException("DONE");
                    }
                }
            });
        }
        catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        catch (SAXParseCompleteException e) {
            return catalogue;
        }
        catch (SAXException ex) {
            throw new XmlException(ex);
        }
        throw new XmlException("Invalid catalogue XML");
    }

    /**
     * Buffered internally.
     * 
     * @param inputStream
     * @return
     * @throws XmlException
     * @throws IOException 
     */
    private GameSystem readGameSystem(ByteArrayInputStream inputStream) throws XmlException, IOException {
        final GameSystem gameSystem = new GameSystem();
        try {
            SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
            saxParser.parse(inputStream, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if (qName.equalsIgnoreCase(DataConstants.GAME_SYSTEM_TAG)) {
                        gameSystem.setId(attributes.getValue(DataConstants.ID_ATTRIBUTE));
                        gameSystem.setBattleScribeVersion(attributes.getValue(DataConstants.BATTLESCRIBE_VERSION_ATTRIBUTE));
                        gameSystem.setRevision(Integer.parseInt(attributes.getValue(DataConstants.REVISION_ATTRIBUTE)));
                        gameSystem.setName(attributes.getValue(DataConstants.NAME_ATTRIBUTE));
                        gameSystem.setAuthorName(attributes.getValue(DataConstants.AUTHOR_NAME_ATTRIBUTE));
                        gameSystem.setAuthorContact(attributes.getValue(DataConstants.AUTHOR_CONTACT_ATTRIBUTE));
                        gameSystem.setAuthorUrl(attributes.getValue(DataConstants.AUTHOR_URL_ATTRIBUTE));
                        throw new SAXParseCompleteException("DONE");
                    }
                }
            });
        }
        catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        catch (SAXParseCompleteException e) {
            return gameSystem;
        }
        catch (SAXException ex) {
            throw new XmlException(ex);
        }
        throw new XmlException("Invalid catalogue XML");
    }

    /**
     * Buffered internally.
     * 
     * @param inputStream
     * @return
     * @throws XmlException
     * @throws IOException 
     */
    private Roster readRoster(ByteArrayInputStream inputStream) throws XmlException, IOException {
        final Roster roster = new Roster();
        try {
            SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
            saxParser.parse(inputStream, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if (qName.equalsIgnoreCase(DataConstants.ROSTER_TAG)) {
                        roster.setBattleScribeVersion(attributes.getValue(DataConstants.BATTLESCRIBE_VERSION_ATTRIBUTE));
                        roster.setDescription(attributes.getValue(DataConstants.DESCRIPTION_ATTRIBUTE));
                        roster.setName(attributes.getValue(DataConstants.NAME_ATTRIBUTE));
                        roster.setPoints(Double.parseDouble(attributes.getValue(DataConstants.POINTS_ATTRIBUTE)));
                        roster.setPointsLimit(Double.parseDouble(attributes.getValue(DataConstants.POINTS_LIMIT_ATTRIBUTE)));
                        roster.setGameSystemId(attributes.getValue(DataConstants.GAME_SYSTEM_ID_ATTRIBUTE));
                        
                        if (attributes.getIndex(DataConstants.GAME_SYSTEM_NAME_ATTRIBUTE) >= 0) {
                            roster.setGameSystemName(attributes.getValue(DataConstants.GAME_SYSTEM_NAME_ATTRIBUTE));
                        }
                        if (attributes.getIndex(DataConstants.GAME_SYSTEM_REVISION_ATTRIBUTE) >= 0) {
                            roster.setGameSystemRevision(
                                    Integer.parseInt(attributes.getValue(DataConstants.GAME_SYSTEM_REVISION_ATTRIBUTE)));
                        }
                        
                        throw new SAXParseCompleteException("DONE");
                    }
                }
            });
        }
        catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        catch (SAXParseCompleteException e) {
            return roster;
        }
        catch (SAXException ex) {
            throw new XmlException(ex);
        }
        throw new XmlException("Invalid roster XML");
    }
}
