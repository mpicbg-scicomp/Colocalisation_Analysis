package results;

import algorithms.Histogram2D;

import com.itextpdf.text.BadElementException;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfWriter;

import gadgets.DataContainer;
import gadgets.DataContainer.MaskType;
import ij.IJ;
import ij.ImagePlus;
import ij.io.SaveDialog;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.math.ImageStatistics;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.LongType;


public class PDFWriter<T extends RealType<T>> implements ResultHandler<T> {

	// indicates if we want to produce US letter or A4 size
	boolean isLetter = false;
	// indicates if the content is the first item on the page
	boolean isFirst  = true;
	// show the name of the image
	static boolean showName=true;
	// a static counter for this sessions created PDFs
	static int succeededPrints = 0;
	// show the size in pixels of the image
	static boolean showSize=true;
	// a reference to the data container
	DataContainer<T> container;
	PdfWriter writer;
	Document document;

	// a list of the available result images, no matter what specific kinds
	protected List<com.itextpdf.text.Image> listOfPDFImages
		= new ArrayList<com.itextpdf.text.Image>();
	protected List<Paragraph> listOfPDFTexts
		= new ArrayList<Paragraph>();

	/**
	 * Creates a new PDFWriter that can access the container.
	 *
	 * @param container The data container for source image data
	 */
	public PDFWriter(DataContainer<T> container) {
		this.container = container;
	}

	@Override
	public void handleImage(RandomAccessibleInterval<T> image, String name) {
		ImagePlus imp = ImageJFunctions.wrapFloat( image, name );

		// set the display range
		double max = ImageStatistics.getImageMax(image).getRealDouble();
		imp.setDisplayRange(0.0, max);
		addImageToList(imp, name);
	}

	/**
	 * Handles a histogram the following way: create snapshot, log data, reset the
	 * display range, apply the Fire LUT and finally store it as an iText PDF image.
	 * Afterwards the image is reset to its orignal state again
	 */
	@Override
	public void handleHistogram(Histogram2D<T> histogram, String name) {
		RandomAccessibleInterval<LongType> image = histogram.getPlotImage();
		ImagePlus imp = ImageJFunctions.wrapFloat( image, name );
		// make a snapshot to be able to reset after modifications
		imp.getProcessor().snapshot();
		imp.getProcessor().log();
		imp.updateAndDraw();
		imp.getProcessor().resetMinAndMax();
		IJ.run(imp,"Fire", null);
		addImageToList(imp, name);
		// reset the imp from the log scaling we applied earlier
		imp.getProcessor().reset();
	}

	protected void addImageToList(ImagePlus imp, String name) {
		java.awt.Image awtImage = imp.getImage();
		try {
			com.itextpdf.text.Image pdfImage = com.itextpdf.text.Image.getInstance(awtImage, null);
			pdfImage.setAlt(name); // iText-1.3 setMarkupAttribute("name", name); 
			listOfPDFImages.add(pdfImage);
		}
		catch (BadElementException e) {
			IJ.log("Could not convert image to correct format for PDF generation");
			IJ.handleException(e);
		}
		catch (IOException e) {
			IJ.log("Could not convert image to correct format for PDF generation");
			IJ.handleException(e);
		}
	}

	@Override
	public void handleWarning(Warning warning) {
		listOfPDFTexts.add(new Paragraph("Warning! " + warning.getShortMessage() + " - " + warning.getLongMessage()));
	}

	@Override
	public void handleValue(String name, String value) {
		// send (output parameter name, value)  to IJ.log for scraping batch results
		IJ.log(name + ", " + value);
		listOfPDFTexts.add(new Paragraph(name + ": " + value));
	}

	@Override
	public void handleValue(String name, double value) {
		handleValue(name, value, 3);
	}

	@Override
	public void handleValue(String name, double value, int decimals) {
		listOfPDFTexts.add(new Paragraph(name + ": " + IJ.d2s(value, decimals)));
	}

	/**
	 * Prints an image into the opened PDF.
	 * @param img The image to print.
	 * @param printName The name to print under the image.
	 */
	protected void addImage(com.itextpdf.text.Image image)
			throws DocumentException, IOException {

		if (! isFirst) {
			document.add(new Paragraph("\n"));
			float vertPos = writer.getVerticalPosition(true);
			if (vertPos - document.bottom() < image.getHeight()) {
				document.newPage();
			} else {
				PdfContentByte cb = writer.getDirectContent();
				cb.setLineWidth(1f);
				if (isLetter) {
					cb.moveTo(PageSize.LETTER.getLeft(50), vertPos);
					cb.lineTo(PageSize.LETTER.getRight(50), vertPos);
				} else {
					cb.moveTo(PageSize.A4.getLeft(50), vertPos);
					cb.lineTo(PageSize.A4.getRight(50), vertPos);
				}
				cb.stroke();
			}
		}

		if (showName) {
			Paragraph paragraph = new Paragraph(image.getAlt()); // iText-1.3: getMarkupAttribute("name"));
			paragraph.setAlignment(Paragraph.ALIGN_CENTER);
			document.add(paragraph);
			//spcNm = 40;
		}

		if (showSize) {
			Paragraph paragraph = new Paragraph(image.getWidth() + " x " + image.getHeight());
			paragraph.setAlignment(Paragraph.ALIGN_CENTER);
			document.add(paragraph);
			//spcSz = 40;
		}

		image.setAlignment(com.itextpdf.text.Image.ALIGN_CENTER);
		document.add(image);
		isFirst = false;
	}

	@Override
	public void process() {
		try {
			// Use the getJobName() in DataContainer for the job name.
			String jobName = container.getJobName();

			/* If a mask is in use, add a counter
			 * information to the jobName.
			 */
			if (container.getMaskType() != MaskType.None) {
				// maskHash is now used as the mask or ROI unique ID in the
				// jobName but we can still increment and use succeededPrints at
				// the end of the filename for PDFs when there is a mask.
				jobName += (succeededPrints + 1);
			}
			// get the path to the file we are about to create
			SaveDialog sd = new SaveDialog("Save as PDF", jobName, ".pdf");
			// update jobName if the user changes it in the save file dialog.
			jobName = sd.getFileName();
			String directory = sd.getDirectory();
			// make sure we have what we need next
			if ((jobName == null) || (directory == null)) {
				return;
			}
			String path = directory+jobName;
			// create a new iText Document and add date and title
			document = new Document(isLetter ? PageSize.LETTER : PageSize.A4);
			document.addCreationDate();
			document.addTitle(jobName);
			// get a writer object to do the actual output
			writer = PdfWriter.getInstance(document, new FileOutputStream(path));
			document.open();

			// write job name at the top of the PDF file as a title
			Paragraph titlePara = new Paragraph(jobName);
			document.add(titlePara);

			// iterate over all produced images
			for (com.itextpdf.text.Image img : listOfPDFImages) {
				addImage(img);
			}

			//iterate over all produced text objects
			for (Paragraph p : listOfPDFTexts) {
				document.add(p);
			}
		} catch(DocumentException de) {
			IJ.showMessage("PDF Writer", de.getMessage());
		} catch(IOException ioe) {
			IJ.showMessage("PDF Writer", ioe.getMessage());
		} finally {
			if (document !=null) {
				document.close();
				succeededPrints++;
			}
		}
	}
}
