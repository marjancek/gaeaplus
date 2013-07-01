/*
 * Copyright (C) 2013 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration.
 * All Rights Reserved.
 */

package gov.nasa.worldwindx.applications.dataimporter;

import gov.nasa.worldwind.*;
import gov.nasa.worldwind.avlist.*;
import gov.nasa.worldwind.cache.FileStore;
import gov.nasa.worldwind.data.*;
import gov.nasa.worldwind.exception.WWRuntimeException;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.globes.ElevationModel;
import gov.nasa.worldwind.layers.*;
import gov.nasa.worldwind.render.*;
import gov.nasa.worldwind.terrain.CompoundElevationModel;
import gov.nasa.worldwind.util.*;
import gov.nasa.worldwindx.examples.ApplicationTemplate;
import gov.nasa.worldwindx.examples.util.ExampleUtil;
import org.w3c.dom.*;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.util.*;

/**
 * Handles all the work necessary to install tiled image layers and elevation models.
 *
 * @author tag
 * @version $Id: DataInstaller.java 1180 2013-02-15 18:40:47Z tgaskins $
 */
public class DataInstaller extends AVListImpl
{
    public static final String IMAGERY = "Imagery";
    public static final String ELEVATION = "Elevation";
    public static final String INSTALL_COMPLETE = "gov.nasa.worldwindx.dataimport.DataInstaller.InstallComplete";
    public static final String PREVIEW_LAYER = "gov.nasa.worldwindx.dataimport.DataInstaller.PreviewLayer";

    public Document installDataFromFiles(Component parentComponent, FileSet fileSet) throws Exception
    {
        // Create a DataStoreProducer that is capable of processing the file.
        final DataStoreProducer producer = createDataStoreProducerFromFiles(fileSet);

        File installLocation = this.getDefaultInstallLocation(WorldWind.getDataFileStore());
        if (installLocation == null)
        {
            String message = Logging.getMessage("generic.NoDefaultImportLocation");
            Logging.logger().severe(message);
            return null;
        }

        String datasetName = askForDatasetName(suggestDatasetName(fileSet));

        DataInstallerProgressMonitor progressMonitor = new DataInstallerProgressMonitor(parentComponent, producer);
        Document doc = null;
        try
        {
            // Install the file into the specified FileStore.
            progressMonitor.start();
            doc = createDataStore(fileSet, installLocation, datasetName, producer);

            // Create a raster server configuration document if the installation was successful.
            // The raster server document enables the layer or elevation model (created to display this data)
            // to create tiles from the original sources at runtime.
            createRasterServerConfigDoc(WorldWind.getDataFileStore(), producer);

            // The user clicked the ProgressMonitor's "Cancel" button. Revert any change made during production,
            // and
            // discard the returned DataConfiguration reference.
            if (progressMonitor.isCanceled())
            {
                doc = null;
                producer.removeProductionState();
            }
        }
        finally
        {
            progressMonitor.stop();
        }

        return doc;
    }

    protected DataStoreProducer createDataStoreProducerFromFiles(FileSet fileSet) throws IllegalArgumentException
    {
        if (fileSet == null || fileSet.getLength() == 0)
        {
            String message = Logging.getMessage("nullValue.ArrayIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        String commonPixelFormat = this.determineCommonPixelFormat(fileSet);

        if (AVKey.IMAGE.equals(commonPixelFormat))
        {
            return new TiledImageProducer();
        }
        else if (AVKey.ELEVATION.equals(commonPixelFormat))
        {
            return new TiledElevationProducer();
        }

        String message = Logging.getMessage("generic.UnexpectedRasterType", commonPixelFormat);
        Logging.logger().severe(message);
        throw new IllegalArgumentException(message);
    }

    protected String determineCommonPixelFormat(FileSet fileSet) throws IllegalArgumentException
    {
        if (fileSet == null || fileSet.getLength() == 0)
        {
            String message = Logging.getMessage("nullValue.ArrayIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        String commonPixelFormat = null;

        for (File file : fileSet.getFiles())
        {
            AVList params = new AVListImpl();
            if (this.isDataRaster(file, params))
            {
                String pixelFormat = params.getStringValue(AVKey.PIXEL_FORMAT);
                if (WWUtil.isEmpty(commonPixelFormat))
                {
                    if (WWUtil.isEmpty(pixelFormat))
                    {
                        String message = Logging.getMessage("generic.UnrecognizedSourceType",
                            file.getAbsolutePath());
                        Logging.logger().severe(message);
                        throw new IllegalArgumentException(message);
                    }
                    else
                    {
                        commonPixelFormat = pixelFormat;
                    }
                }
                else if (commonPixelFormat != null && !commonPixelFormat.equals(pixelFormat))
                {
                    if (WWUtil.isEmpty(pixelFormat))
                    {
                        String message = Logging.getMessage("generic.UnrecognizedSourceType",
                            file.getAbsolutePath());
                        Logging.logger().severe(message);
                        throw new IllegalArgumentException(message);
                    }
                    else
                    {
                        String reason = Logging.getMessage("generic.UnexpectedRasterType", pixelFormat);
                        String details = file.getAbsolutePath() + ": " + reason;
                        String message = Logging.getMessage("DataRaster.IncompatibleRaster", details);
                        Logging.logger().severe(message);
                        throw new IllegalArgumentException(message);
                    }
                }
            }
        }

        return commonPixelFormat;
    }

    protected Document createDataStore(FileSet fileSet, File installLocation, String datasetName,
                                       DataStoreProducer producer) throws Exception
    {
        // Create the production parameters. These parameters instruct the DataStoreProducer where to install the
        // cached data, and what name to put in the data configuration document.
        AVList params = new AVListImpl();

        params.setValue(AVKey.DATASET_NAME, datasetName);
        params.setValue(AVKey.DATA_CACHE_NAME, datasetName);
        params.setValue(AVKey.FILE_STORE_LOCATION, installLocation.getAbsolutePath());

        // These parameters define producer's behavior:
        // create a full tile cache OR generate only first two low resolution levels
        boolean enableFullPyramid = Configuration.getBooleanValue(AVKey.PRODUCER_ENABLE_FULL_PYRAMID, false);
        if (!enableFullPyramid)
        {
            params.setValue(AVKey.SERVICE_NAME, AVKey.SERVICE_NAME_LOCAL_RASTER_SERVER);
            // retrieve the value of the AVKey.TILED_RASTER_PRODUCER_LIMIT_MAX_LEVEL, default to "Auto" if missing
            String maxLevel = Configuration.getStringValue(AVKey.TILED_RASTER_PRODUCER_LIMIT_MAX_LEVEL, "0");
            params.setValue(AVKey.TILED_RASTER_PRODUCER_LIMIT_MAX_LEVEL, maxLevel);
        }
        else
        {
            params.setValue(AVKey.PRODUCER_ENABLE_FULL_PYRAMID, true);
        }

        producer.setStoreParameters(params);

        try
        {
            for (File file : fileSet.getFiles())
            {
                producer.offerDataSource(file, null);
                Thread.yield();
            }

            // Convert the file to a form usable by World Wind components,
            // according to the specified DataStoreProducer.
            // This throws an exception if production fails for any reason.
            producer.startProduction();
        }
        catch (InterruptedException ie)
        {
            producer.removeProductionState();
            Thread.interrupted();
            throw ie;
        }
        catch (Exception e)
        {
            // Exception attempting to convert the file. Revert any change made during production.
            producer.removeProductionState();
            throw e;
        }

        // Return the DataConfiguration from the production results. Since production successfully completed, the
        // DataStoreProducer should contain a DataConfiguration in the production results. We test the production
        // results anyway.
        Iterable results = producer.getProductionResults();
        if (results != null && results.iterator() != null && results.iterator().hasNext())
        {
            Object o = results.iterator().next();
            if (o != null && o instanceof Document)
            {
                return (Document) o;
            }
        }

        return null;
    }

    protected String askForDatasetName(String suggestedName)
    {
        String datasetName = suggestedName;

        for (; ; )
        {
            Object o = JOptionPane.showInputDialog(null, "Name:", "Enter dataset name",
                JOptionPane.QUESTION_MESSAGE, null, null, datasetName);

            if (!(o instanceof String)) // user canceled the input
            {
                Thread.interrupted();

                String msg = Logging.getMessage("generic.OperationCancelled", "Import");
                Logging.logger().info(msg);
                throw new WWRuntimeException(msg);
            }

            return WWIO.replaceIllegalFileNameCharacters((String) o);
        }
    }

    protected String suggestDatasetName(FileSet fileSet)
    {
        if (null == fileSet || fileSet.getLength() == 0)
        {
            return null;
        }

        if (fileSet.getName() != null)
            return fileSet.getScale() != null ? fileSet.getName() + " " + fileSet.getScale() : fileSet.getName();

        // extract file and folder names that all files have in common
        StringBuilder sb = new StringBuilder();
        for (File file : fileSet.getFiles())
        {
            String name = file.getAbsolutePath();
            if (WWUtil.isEmpty(name))
            {
                continue;
            }

            name = WWIO.replaceIllegalFileNameCharacters(WWIO.replaceSuffix(name, ""));

            if (sb.length() == 0)
            {
                sb.append(name);
                continue;
            }
            else
            {
                int size = Math.min(name.length(), sb.length());
                for (int i = 0; i < size; i++)
                {
                    if (name.charAt(i) != sb.charAt(i))
                    {
                        sb.setLength(i);
                        break;
                    }
                }
            }
        }

        String name = sb.toString();
        sb.setLength(0);

        ArrayList<String> words = new ArrayList<String>();

        StringTokenizer tokens = new StringTokenizer(name, " _:/\\-=!@#$%^&()[]{}|\".,<>;`+");
        String lastWord = null;
        while (tokens.hasMoreTokens())
        {
            String word = tokens.nextToken();
            // discard empty, one-char long, and duplicated keys
            if (WWUtil.isEmpty(word) || word.length() < 2 || word.equalsIgnoreCase(lastWord))
            {
                continue;
            }

            lastWord = word;

            words.add(word);
            if (words.size() > 4)  // let's keep only last four words
            {
                words.remove(0);
            }
        }

        if (words.size() > 0)
        {
            sb.setLength(0);
            for (String word : words)
            {
                sb.append(word).append(' ');
            }
            sb.append(fileSet.isImagery() ? " Imagery" : fileSet.isElevation() ? " Elevations" : "");
            return sb.toString().trim();
        }
        else
        {
            return (WWUtil.isEmpty(name)) ? "change me" : name;
        }
    }

    protected void createRasterServerConfigDoc(FileStore fileStore, DataStoreProducer producer)
    {
        AVList productionParams = (null != producer) ? producer.getProductionParameters() : new AVListImpl();
        productionParams = (null == productionParams) ? new AVListImpl() : productionParams;

        if (!AVKey.SERVICE_NAME_LOCAL_RASTER_SERVER.equals(productionParams.getValue(AVKey.SERVICE_NAME)))
        {
            // *.RasterServer.xml is not required
            return;
        }

        File installLocation = this.getDefaultInstallLocation(fileStore);
        if (installLocation == null)
        {
            String message = Logging.getMessage("generic.NoDefaultImportLocation");
            Logging.logger().severe(message);
            return;
        }

        Document doc = WWXML.createDocumentBuilder(true).newDocument();

        Element root = WWXML.setDocumentElement(doc, "RasterServer");
        WWXML.setTextAttribute(root, "version", "1.0");

        StringBuilder sb = new StringBuilder();
        sb.append(installLocation.getAbsolutePath()).append(File.separator);

        if (!productionParams.hasKey(AVKey.DATA_CACHE_NAME))
        {
            String message = Logging.getMessage("generic.MissingRequiredParameter", AVKey.DATA_CACHE_NAME);
            Logging.logger().severe(message);
            throw new WWRuntimeException(message);
        }
        sb.append(productionParams.getValue(AVKey.DATA_CACHE_NAME)).append(File.separator);

        if (!productionParams.hasKey(AVKey.DATASET_NAME))
        {
            String message = Logging.getMessage("generic.MissingRequiredParameter", AVKey.DATASET_NAME);
            Logging.logger().severe(message);
            throw new WWRuntimeException(message);
        }
        sb.append(productionParams.getValue(AVKey.DATASET_NAME)).append(".RasterServer.xml");

        Object o = productionParams.getValue(AVKey.DISPLAY_NAME);
        if (WWUtil.isEmpty(o))
        {
            productionParams.setValue(AVKey.DISPLAY_NAME, productionParams.getValue(AVKey.DATASET_NAME));
        }

        String rasterServerConfigFilePath = sb.toString();

        Sector extent = null;
        if (productionParams.hasKey(AVKey.SECTOR))
        {
            o = productionParams.getValue(AVKey.SECTOR);
            if (null != o && o instanceof Sector)
            {
                extent = (Sector) o;
            }
        }

        if (null != extent)
        {
            WWXML.appendSector(root, "Sector", extent);
        }
        else
        {
            String message = Logging.getMessage("generic.MissingRequiredParameter", AVKey.SECTOR);
            Logging.logger().severe(message);
            throw new WWRuntimeException(message);
        }

        Element sources = doc.createElementNS(null, "Sources");
        if (producer instanceof TiledRasterProducer)
        {
            for (DataRaster raster : ((TiledRasterProducer) producer).getDataRasters())
            {
                if (raster instanceof CachedDataRaster)
                {
                    try
                    {
                        appendSource(sources, (CachedDataRaster) raster);
                    }
                    catch (Throwable t)
                    {
                        String reason = WWUtil.extractExceptionReason(t);
                        Logging.logger().warning(reason);
//                        Logging.logger().severe(reason);
//                        throw new WWRuntimeException(reason);
                    }
                }
                else
                {
                    String message = Logging.getMessage("TiledRasterProducer.UnrecognizedRasterType",
                        raster.getClass().getName(), raster.getStringValue(AVKey.DATASET_NAME));
                    Logging.logger().severe(message);
                    throw new WWRuntimeException(message);
                }
            }
        }

        AVList rasterServerProperties = new AVListImpl();

        String[] keysToCopy = new String[]{AVKey.DATA_CACHE_NAME, AVKey.DATASET_NAME, AVKey.DISPLAY_NAME};
        WWUtil.copyValues(productionParams, rasterServerProperties, keysToCopy, false);

        appendProperties(root, rasterServerProperties);

        // add sources
        root.appendChild(sources);

        WWXML.saveDocumentToFile(doc, rasterServerConfigFilePath);
    }

    protected void appendProperties(Element context, AVList properties)
    {
        if (null == context || properties == null)
        {
            return;
        }

        StringBuilder sb = new StringBuilder();

        // add properties
        for (Map.Entry<String, Object> entry : properties.getEntries())
        {
            sb.setLength(0);
            String key = entry.getKey();
            sb.append(properties.getValue(key));
            String value = sb.toString();
            if (WWUtil.isEmpty(key) || WWUtil.isEmpty(value))
            {
                continue;
            }

            Element property = WWXML.appendElement(context, "Property");
            WWXML.setTextAttribute(property, "name", key);
            WWXML.setTextAttribute(property, "value", value);
        }
    }

    protected void appendSource(Element sources, CachedDataRaster raster) throws WWRuntimeException
    {
        Object o = raster.getDataSource();
        if (WWUtil.isEmpty(o))
        {
            String message = Logging.getMessage("nullValue.DataSourceIsNull");
            Logging.logger().fine(message);
            throw new WWRuntimeException(message);
        }

        File f = WWIO.getFileForLocalAddress(o);
        if (WWUtil.isEmpty(f))
        {
            String message = Logging.getMessage("TiledRasterProducer.UnrecognizedDataSource", o);
            Logging.logger().fine(message);
            throw new WWRuntimeException(message);
        }

        Element source = WWXML.appendElement(sources, "Source");
        WWXML.setTextAttribute(source, "type", "file");
        WWXML.setTextAttribute(source, "path", f.getAbsolutePath());

        AVList params = raster.getParams();
        if (null == params)
        {
            String message = Logging.getMessage("nullValue.ParamsIsNull");
            Logging.logger().fine(message);
            throw new WWRuntimeException(message);
        }

        Sector sector = raster.getSector();
        if (null == sector && params.hasKey(AVKey.SECTOR))
        {
            o = params.getValue(AVKey.SECTOR);
            if (o instanceof Sector)
            {
                sector = (Sector) o;
            }
        }

        if (null != sector)
        {
            WWXML.appendSector(source, "Sector", sector);
        }
    }

    public boolean isDataRaster(Object source, AVList params)
    {
        if (source == null)
        {
            String message = Logging.getMessage("nullValue.SourceIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        DataRasterReaderFactory readerFactory;
        try
        {
            readerFactory = (DataRasterReaderFactory) WorldWind.createConfigurationComponent(
                AVKey.DATA_RASTER_READER_FACTORY_CLASS_NAME);
        }
        catch (Exception e)
        {
            readerFactory = new BasicDataRasterReaderFactory();
        }

        params = (null == params) ? new AVListImpl() : params;
        DataRasterReader reader = readerFactory.findReaderFor(source, params);
        if (reader == null)
        {
            return false;
        }

        if (!params.hasKey(AVKey.PIXEL_FORMAT))
        {
            try
            {
                reader.readMetadata(source, params);
            }
            catch (Exception e)
            {
                String message = Logging.getMessage("generic.ExceptionWhileReading", e.getMessage());
                Logging.logger().finest(message);
            }
        }

        return AVKey.IMAGE.equals(params.getStringValue(AVKey.PIXEL_FORMAT))
            || AVKey.ELEVATION.equals(params.getStringValue(AVKey.PIXEL_FORMAT));
    }

    public File getDefaultInstallLocation(FileStore fileStore)
    {
        if (fileStore == null)
        {
            String message = Logging.getMessage("nullValue.FileStoreIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        for (File location : fileStore.getLocations())
        {
            if (fileStore.isInstallLocation(location.getPath()))
            {
                return location;
            }
        }

        return fileStore.getWriteLocation();
    }

    public static void addToWorldWindow(WorldWindow wwd, Element domElement, AVList dataSet, boolean goTo)
    {
        String type = DataConfigurationUtils.getDataConfigType(domElement);
        if (type == null)
            return;

        if (type.equalsIgnoreCase("Layer"))
        {
            addLayerToWorldWindow(wwd, domElement, dataSet, goTo);
        }
        else if (type.equalsIgnoreCase("ElevationModel"))
        {
            addElevationModelToWorldWindow(wwd, domElement, dataSet, true);
        }
    }

    public static void addLayerToWorldWindow(WorldWindow wwd, Element domElement, AVList dataSet, boolean goTo)
    {
        Layer layer = null;
        try
        {
            Factory factory = (Factory) WorldWind.createConfigurationComponent(AVKey.LAYER_FACTORY);
            layer = (Layer) factory.createFromConfigSource(domElement, null);

            Sector sector = WWXML.getSector(domElement, "Sector", null);
            layer.setValue(AVKey.SECTOR, sector);
            dataSet.setValue(AVKey.DISPLAY_NAME, layer.getName());
        }
        catch (Exception e)
        {
            String message = Logging.getMessage("generic.CreationFromConfigurationFailed",
                DataConfigurationUtils.getDataConfigDisplayName(domElement));
            Logging.logger().log(java.util.logging.Level.SEVERE, message, e);
        }

        if (layer == null)
            return;

        layer.setEnabled(true); // BasicLayerFactory creates layer which is initially disabled

        Layer existingLayer = findLayer(wwd, dataSet.getStringValue(AVKey.DISPLAY_NAME));
        if (existingLayer != null)
            wwd.getModel().getLayers().remove(existingLayer);

        removeLayerPreview(wwd, dataSet);

        ApplicationTemplate.insertBeforePlacenames(wwd, layer);

        final Sector sector = (Sector) layer.getValue(AVKey.SECTOR);
        if (goTo && sector != null && !sector.equals(Sector.FULL_SPHERE))
        {
            ExampleUtil.goTo(wwd, sector);
        }
    }

    protected static void removeLayerPreview(WorldWindow wwd, AVList dataSet)
    {
        Layer layer = (Layer) dataSet.getValue(AVKey.LAYER);
        if (layer == null || layer.getValue(PREVIEW_LAYER) == null)
            return;

        if (! (layer instanceof RenderableLayer))
            return;

        SurfaceImage surfaceImage = null;
        RenderableLayer renderableLayer = (RenderableLayer) layer;
        for (Renderable renderable : renderableLayer.getRenderables())
        {
            if (renderable instanceof SurfaceImage)
            {
                surfaceImage = (SurfaceImage) renderable;
                break;
            }
        }

        if (surfaceImage != null)
            renderableLayer.removeRenderable(surfaceImage);
//
//        wwd.getModel().getLayers().remove(layer);
    }

    public static void addElevationModelToWorldWindow(WorldWindow wwd, Element domElement, AVList dataSet,
                                                      boolean goTo)
    {
        ElevationModel elevationModel = null;
        try
        {
            Factory factory = (Factory) WorldWind.createConfigurationComponent(AVKey.ELEVATION_MODEL_FACTORY);
            elevationModel = (ElevationModel) factory.createFromConfigSource(domElement, null);
//            elevationModel.setValue(AVKey.DATASET_NAME, dataSet.getStringValue(AVKey.DATASET_NAME));
            dataSet.setValue(AVKey.DISPLAY_NAME, elevationModel.getName());

            // TODO: set Sector as in addLayerToWorldWindow?
        }
        catch (Exception e)
        {
            String message = Logging.getMessage("generic.CreationFromConfigurationFailed",
                DataConfigurationUtils.getDataConfigDisplayName(domElement));
            Logging.logger().log(java.util.logging.Level.SEVERE, message, e);
        }

        if (elevationModel == null)
            return;

        ElevationModel existingElevationModel = findElevationModel(wwd, dataSet.getStringValue(AVKey.DISPLAY_NAME));
        if (existingElevationModel != null)
            removeElevationModel(wwd, existingElevationModel);

        ElevationModel defaultElevationModel = wwd.getModel().getGlobe().getElevationModel();
        if (defaultElevationModel instanceof CompoundElevationModel)
        {
            if (!((CompoundElevationModel) defaultElevationModel).containsElevationModel(elevationModel))
            {
                ((CompoundElevationModel) defaultElevationModel).addElevationModel(elevationModel);
            }
        }
        else
        {
            CompoundElevationModel cm = new CompoundElevationModel();
            cm.addElevationModel(defaultElevationModel);
            cm.addElevationModel(elevationModel);
            wwd.getModel().getGlobe().setElevationModel(cm);
        }

        Sector sector = (Sector) elevationModel.getValue(AVKey.SECTOR);
        if (goTo && sector != null && !sector.equals(Sector.FULL_SPHERE))
        {
            ExampleUtil.goTo(wwd, sector);
        }

        wwd.firePropertyChange(new PropertyChangeEvent(wwd, AVKey.ELEVATION_MODEL, null, elevationModel));
    }

    public static DataRasterReaderFactory getReaderFactory()
    {
        try
        {
            return (DataRasterReaderFactory) WorldWind.createConfigurationComponent(
                AVKey.DATA_RASTER_READER_FACTORY_CLASS_NAME);
        }
        catch (Exception e)
        {
            return new BasicDataRasterReaderFactory();
        }
    }

    public static Layer findLayer(WorldWindow wwd, String layerName)
    {
        for (Layer layer : wwd.getModel().getLayers())
        {
            String dataSetName = layer.getStringValue(AVKey.DISPLAY_NAME);
            if (dataSetName != null && dataSetName.equals(layerName))
                return layer;
        }

        return null;
    }

    public static ElevationModel findElevationModel(WorldWindow wwd, String elevationModelName)
    {
        ElevationModel defaultElevationModel = wwd.getModel().getGlobe().getElevationModel();
        if (defaultElevationModel instanceof CompoundElevationModel)
        {
            CompoundElevationModel cm = (CompoundElevationModel) defaultElevationModel;
            for (ElevationModel em : cm.getElevationModels())
            {
                String name = em.getStringValue(AVKey.DISPLAY_NAME);
                if (name != null && name.equals(elevationModelName))
                    return em;
            }
        }
        else
        {
            String name = defaultElevationModel.getStringValue(AVKey.DISPLAY_NAME);
            if (name != null && name.equals(elevationModelName))
                return defaultElevationModel;
        }

        return null;
    }

    public static void removeElevationModel(WorldWindow wwd, ElevationModel elevationModel)
    {
        ElevationModel defaultElevationModel = wwd.getModel().getGlobe().getElevationModel();
        if (defaultElevationModel instanceof CompoundElevationModel)
        {
            CompoundElevationModel cm = (CompoundElevationModel) defaultElevationModel;
            for (ElevationModel em : cm.getElevationModels())
            {
                String name = em.getStringValue(AVKey.DISPLAY_NAME);
                if (name != null && name.equals(elevationModel.getName()))
                {
                    cm.removeElevationModel(elevationModel);
                    wwd.firePropertyChange(new PropertyChangeEvent(wwd, AVKey.ELEVATION_MODEL, null, elevationModel));
                }
            }
        }
    }
}
