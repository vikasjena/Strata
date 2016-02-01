/**
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.market.curve.node;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.joda.beans.Bean;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.ImmutableDefaults;
import org.joda.beans.ImmutablePreBuild;
import org.joda.beans.ImmutableValidator;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.google.common.collect.ImmutableSet;
import com.opengamma.strata.basics.BuySell;
import com.opengamma.strata.basics.currency.FxRate;
import com.opengamma.strata.basics.date.Tenor;
import com.opengamma.strata.basics.market.FxRateKey;
import com.opengamma.strata.basics.market.MarketData;
import com.opengamma.strata.basics.market.ObservableKey;
import com.opengamma.strata.basics.market.SimpleMarketDataKey;
import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.collect.Messages;
import com.opengamma.strata.market.ValueType;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.DatedCurveParameterMetadata;
import com.opengamma.strata.market.curve.meta.SimpleCurveNodeMetadata;
import com.opengamma.strata.market.curve.meta.TenorCurveNodeMetadata;
import com.opengamma.strata.product.rate.IborRateObservation;
import com.opengamma.strata.product.swap.ExpandedSwapLeg;
import com.opengamma.strata.product.swap.PaymentPeriod;
import com.opengamma.strata.product.swap.RateAccrualPeriod;
import com.opengamma.strata.product.swap.RatePaymentPeriod;
import com.opengamma.strata.product.swap.SwapLeg;
import com.opengamma.strata.product.swap.SwapLegType;
import com.opengamma.strata.product.swap.SwapTrade;
import com.opengamma.strata.product.swap.type.XCcyIborIborSwapTemplate;

/**
 * A curve node whose instrument is a cross-currency Ibor-Ibor interest rate swap.
 * <p>
 * Two market quotes are required, one for the spread and one for the FX rate.
 */
@BeanDefinition
public final class XCcyIborIborSwapCurveNode
    implements CurveNode, ImmutableBean, Serializable {

  /**
   * The template for the swap associated with this node.
   */
  @PropertyDefinition(validate = "notNull")
  private final XCcyIborIborSwapTemplate template;
  /**
   * The key identifying the market data value which provides the spread.
   */
  @PropertyDefinition(validate = "notNull")
  private final ObservableKey spreadKey;
  /**
   * The additional spread added to the market quote.
   */
  @PropertyDefinition
  private final double additionalSpread;
  /**
   * The label to use for the node, defaulted.
   * <p>
   * When building, this will default based on the tenor if not specified.
   */
  @PropertyDefinition(validate = "notEmpty", overrideGet = true)
  private final String label;
  /**
   * The type of date associated to the node. Defaulted to LAST_PAYMENT_DATE.
   */
  @PropertyDefinition
  private final NodeDateType nodeDateType;
  /**
   * The date associated to the node. Used only is the nodeDateType is FIXED_DATE. Null in other cases.
   */
  @PropertyDefinition(get = "field")
  private final LocalDate nodeDate;

  //-------------------------------------------------------------------------
  /**
   * Returns a curve node for a cross-currency Ibor-Ibor interest rate swap using the
   * specified instrument template and rate.
   * <p>
   * A suitable default label will be created.
   *
   * @param template  the template used for building the instrument for the node
   * @param spreadKey  the key identifying the market spread used when building the instrument for the node
   * @return a node whose instrument is built from the template using a market rate
   */
  public static XCcyIborIborSwapCurveNode of(XCcyIborIborSwapTemplate template, ObservableKey spreadKey) {
    return of(template, spreadKey, 0d);
  }

  /**
   * Returns a curve node for a cross-currency Ibor-Ibor interest rate swap using the
   * specified instrument template, rate key and spread.
   * <p>
   * A suitable default label will be created.
   *
   * @param template  the template defining the node instrument
   * @param spreadKey  the key identifying the market spread used when building the instrument for the node
   * @param additionalSpread  the additional spread amount added to the market quote
   * @return a node whose instrument is built from the template using a market rate
   */
  public static XCcyIborIborSwapCurveNode of(
      XCcyIborIborSwapTemplate template,
      ObservableKey spreadKey,
      double additionalSpread) {

    return builder()
        .template(template)
        .spreadKey(spreadKey)
        .additionalSpread(additionalSpread)
        .build();
  }

  /**
   * Returns a curve node for a cross-currency Ibor-Ibor interest rate swap using the
   * specified instrument template, rate key, spread and label.
   *
   * @param template  the template defining the node instrument
   * @param spreadKey  the key identifying the market spread used when building the instrument for the node
   * @param additionalSpread  the additional spread amount added to the market quote
   * @param label  the label to use for the node, if null or empty an appropriate default label will be used
   * @return a node whose instrument is built from the template using a market rate
   */
  public static XCcyIborIborSwapCurveNode of(
      XCcyIborIborSwapTemplate template,
      ObservableKey spreadKey,
      double additionalSpread,
      String label) {

    return new XCcyIborIborSwapCurveNode(template, spreadKey, additionalSpread, label, NodeDateType.LAST_PAYMENT_DATE, null);
  }

  @ImmutablePreBuild
  private static void preBuild(Builder builder) {
    if (builder.label == null && builder.template != null) {
      builder.label = builder.template.getTenor().toString();
    }
  }

  //-------------------------------------------------------------------------
  @Override
  public Set<? extends SimpleMarketDataKey<?>> requirements() {
    return ImmutableSet.of(spreadKey, fxKey());
  }

  @Override
  public DatedCurveParameterMetadata metadata(LocalDate valuationDate) {
    if(nodeDateType.equals(NodeDateType.FIXED_DATE)) {
      return SimpleCurveNodeMetadata.of(nodeDate, label);
    }
    SwapTrade trade = template.toTrade(valuationDate, BuySell.BUY, 1, 1, 0);
    Tenor tenor = template.getTenor();
    if(nodeDateType.equals(NodeDateType.LAST_PAYMENT_DATE)) {
      return TenorCurveNodeMetadata.of(trade.getProduct().getEndDate(), tenor, label);
    }
    if (nodeDateType.equals(NodeDateType.LAST_FIXING_DATE)) {
      SwapLeg iborLeg = trade.getProduct().getLegs(SwapLegType.IBOR).get(1);
      // Select the 'second' Ibor leg, i.e. the flat leg
      ExpandedSwapLeg iborLegExpanded = iborLeg.expand();
      List<PaymentPeriod> periods = iborLegExpanded.getPaymentPeriods();
      int nbPeriods = periods.size();
      RatePaymentPeriod lastPeriod = (RatePaymentPeriod) periods.get(nbPeriods - 1);
      List<RateAccrualPeriod> accruals = lastPeriod.getAccrualPeriods();
      int nbAccruals = accruals.size();
      IborRateObservation ibor = (IborRateObservation) accruals.get(nbAccruals -1).getRateObservation();
      return TenorCurveNodeMetadata.of(ibor.getFixingDate(), tenor, label);
    }
    throw new UnsupportedOperationException("Node date type " + nodeDateType.toString());
  }

  @Override
  public SwapTrade trade(LocalDate valuationDate, MarketData marketData) {
    double marketQuote = marketData.getValue(spreadKey) + additionalSpread;
    FxRate fxRate = marketData.getValue(fxKey());
    double rate = fxRate.fxRate(template.getCurrencyPair());
    return template.toTrade(valuationDate, BuySell.BUY, 1, rate, marketQuote);
  }

  @Override
  public double initialGuess(LocalDate valuationDate, MarketData marketData, ValueType valueType) {
    if (ValueType.DISCOUNT_FACTOR.equals(valueType)) {
      return 1.0d;
    }
    return 0.0d;
  }

  private FxRateKey fxKey() {
    return FxRateKey.of(template.getCurrencyPair());
  }
  
  /**
   * Checks if the type is 'FixedDate'.
   * <p>
   * 
   * @return true if the type is 'FixedDate'
   */
  public boolean isFixedDate() {
    return (nodeDateType == NodeDateType.FIXED_DATE);
  }
  
  /**
   * Gets the node date if the type is 'FixedDate'.
   * <p>
   * If the type is 'FixedDate', this returns the node date.
   * Otherwise, this throws an exception.
   * 
   * @return the node date, only available if the type is 'FixedDate'
   * @throws IllegalStateException if called on a failure result
   */
  public LocalDate getNodeDate() {
    if (!isFixedDate()) {
      throw new IllegalStateException(Messages.format("No currency available for type '{}'", nodeDateType));
    }
    return nodeDate;
  }
  
  @ImmutableValidator
  private void validate() {
    if(nodeDateType.equals(NodeDateType.FIXED_DATE)) {
      ArgChecker.isTrue(nodeDate != null, "Node date must be present when node date type is FIXED_DATE");
    } else {
      ArgChecker.isTrue(nodeDate == null, "Node date must be null when node date type is not FIXED_DATE");      
    }
  }
  
  @ImmutableDefaults
  private static void applyDefaults(Builder builder) {
    builder.nodeDateType = NodeDateType.LAST_PAYMENT_DATE;
    builder.nodeDate = null;
  }

  //------------------------- AUTOGENERATED START -------------------------
  ///CLOVER:OFF
  /**
   * The meta-bean for {@code XCcyIborIborSwapCurveNode}.
   * @return the meta-bean, not null
   */
  public static XCcyIborIborSwapCurveNode.Meta meta() {
    return XCcyIborIborSwapCurveNode.Meta.INSTANCE;
  }

  static {
    JodaBeanUtils.registerMetaBean(XCcyIborIborSwapCurveNode.Meta.INSTANCE);
  }

  /**
   * The serialization version id.
   */
  private static final long serialVersionUID = 1L;

  /**
   * Returns a builder used to create an instance of the bean.
   * @return the builder, not null
   */
  public static XCcyIborIborSwapCurveNode.Builder builder() {
    return new XCcyIborIborSwapCurveNode.Builder();
  }

  private XCcyIborIborSwapCurveNode(
      XCcyIborIborSwapTemplate template,
      ObservableKey spreadKey,
      double additionalSpread,
      String label,
      NodeDateType nodeDateType,
      LocalDate nodeDate) {
    JodaBeanUtils.notNull(template, "template");
    JodaBeanUtils.notNull(spreadKey, "spreadKey");
    JodaBeanUtils.notEmpty(label, "label");
    this.template = template;
    this.spreadKey = spreadKey;
    this.additionalSpread = additionalSpread;
    this.label = label;
    this.nodeDateType = nodeDateType;
    this.nodeDate = nodeDate;
    validate();
  }

  @Override
  public XCcyIborIborSwapCurveNode.Meta metaBean() {
    return XCcyIborIborSwapCurveNode.Meta.INSTANCE;
  }

  @Override
  public <R> Property<R> property(String propertyName) {
    return metaBean().<R>metaProperty(propertyName).createProperty(this);
  }

  @Override
  public Set<String> propertyNames() {
    return metaBean().metaPropertyMap().keySet();
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the template for the swap associated with this node.
   * @return the value of the property, not null
   */
  public XCcyIborIborSwapTemplate getTemplate() {
    return template;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the key identifying the market data value which provides the spread.
   * @return the value of the property, not null
   */
  public ObservableKey getSpreadKey() {
    return spreadKey;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the additional spread added to the market quote.
   * @return the value of the property
   */
  public double getAdditionalSpread() {
    return additionalSpread;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the label to use for the node, defaulted.
   * <p>
   * When building, this will default based on the tenor if not specified.
   * @return the value of the property, not empty
   */
  @Override
  public String getLabel() {
    return label;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the type of date associated to the node. Defaulted to LAST_PAYMENT_DATE.
   * @return the value of the property
   */
  public NodeDateType getNodeDateType() {
    return nodeDateType;
  }

  //-----------------------------------------------------------------------
  /**
   * Returns a builder that allows this bean to be mutated.
   * @return the mutable builder, not null
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj != null && obj.getClass() == this.getClass()) {
      XCcyIborIborSwapCurveNode other = (XCcyIborIborSwapCurveNode) obj;
      return JodaBeanUtils.equal(template, other.template) &&
          JodaBeanUtils.equal(spreadKey, other.spreadKey) &&
          JodaBeanUtils.equal(additionalSpread, other.additionalSpread) &&
          JodaBeanUtils.equal(label, other.label) &&
          JodaBeanUtils.equal(nodeDateType, other.nodeDateType) &&
          JodaBeanUtils.equal(nodeDate, other.nodeDate);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int hash = getClass().hashCode();
    hash = hash * 31 + JodaBeanUtils.hashCode(template);
    hash = hash * 31 + JodaBeanUtils.hashCode(spreadKey);
    hash = hash * 31 + JodaBeanUtils.hashCode(additionalSpread);
    hash = hash * 31 + JodaBeanUtils.hashCode(label);
    hash = hash * 31 + JodaBeanUtils.hashCode(nodeDateType);
    hash = hash * 31 + JodaBeanUtils.hashCode(nodeDate);
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder(224);
    buf.append("XCcyIborIborSwapCurveNode{");
    buf.append("template").append('=').append(template).append(',').append(' ');
    buf.append("spreadKey").append('=').append(spreadKey).append(',').append(' ');
    buf.append("additionalSpread").append('=').append(additionalSpread).append(',').append(' ');
    buf.append("label").append('=').append(label).append(',').append(' ');
    buf.append("nodeDateType").append('=').append(nodeDateType).append(',').append(' ');
    buf.append("nodeDate").append('=').append(JodaBeanUtils.toString(nodeDate));
    buf.append('}');
    return buf.toString();
  }

  //-----------------------------------------------------------------------
  /**
   * The meta-bean for {@code XCcyIborIborSwapCurveNode}.
   */
  public static final class Meta extends DirectMetaBean {
    /**
     * The singleton instance of the meta-bean.
     */
    static final Meta INSTANCE = new Meta();

    /**
     * The meta-property for the {@code template} property.
     */
    private final MetaProperty<XCcyIborIborSwapTemplate> template = DirectMetaProperty.ofImmutable(
        this, "template", XCcyIborIborSwapCurveNode.class, XCcyIborIborSwapTemplate.class);
    /**
     * The meta-property for the {@code spreadKey} property.
     */
    private final MetaProperty<ObservableKey> spreadKey = DirectMetaProperty.ofImmutable(
        this, "spreadKey", XCcyIborIborSwapCurveNode.class, ObservableKey.class);
    /**
     * The meta-property for the {@code additionalSpread} property.
     */
    private final MetaProperty<Double> additionalSpread = DirectMetaProperty.ofImmutable(
        this, "additionalSpread", XCcyIborIborSwapCurveNode.class, Double.TYPE);
    /**
     * The meta-property for the {@code label} property.
     */
    private final MetaProperty<String> label = DirectMetaProperty.ofImmutable(
        this, "label", XCcyIborIborSwapCurveNode.class, String.class);
    /**
     * The meta-property for the {@code nodeDateType} property.
     */
    private final MetaProperty<NodeDateType> nodeDateType = DirectMetaProperty.ofImmutable(
        this, "nodeDateType", XCcyIborIborSwapCurveNode.class, NodeDateType.class);
    /**
     * The meta-property for the {@code nodeDate} property.
     */
    private final MetaProperty<LocalDate> nodeDate = DirectMetaProperty.ofImmutable(
        this, "nodeDate", XCcyIborIborSwapCurveNode.class, LocalDate.class);
    /**
     * The meta-properties.
     */
    private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(
        this, null,
        "template",
        "spreadKey",
        "additionalSpread",
        "label",
        "nodeDateType",
        "nodeDate");

    /**
     * Restricted constructor.
     */
    private Meta() {
    }

    @Override
    protected MetaProperty<?> metaPropertyGet(String propertyName) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return template;
        case 1302780908:  // spreadKey
          return spreadKey;
        case 291232890:  // additionalSpread
          return additionalSpread;
        case 102727412:  // label
          return label;
        case 937712682:  // nodeDateType
          return nodeDateType;
        case 1122582736:  // nodeDate
          return nodeDate;
      }
      return super.metaPropertyGet(propertyName);
    }

    @Override
    public XCcyIborIborSwapCurveNode.Builder builder() {
      return new XCcyIborIborSwapCurveNode.Builder();
    }

    @Override
    public Class<? extends XCcyIborIborSwapCurveNode> beanType() {
      return XCcyIborIborSwapCurveNode.class;
    }

    @Override
    public Map<String, MetaProperty<?>> metaPropertyMap() {
      return metaPropertyMap$;
    }

    //-----------------------------------------------------------------------
    /**
     * The meta-property for the {@code template} property.
     * @return the meta-property, not null
     */
    public MetaProperty<XCcyIborIborSwapTemplate> template() {
      return template;
    }

    /**
     * The meta-property for the {@code spreadKey} property.
     * @return the meta-property, not null
     */
    public MetaProperty<ObservableKey> spreadKey() {
      return spreadKey;
    }

    /**
     * The meta-property for the {@code additionalSpread} property.
     * @return the meta-property, not null
     */
    public MetaProperty<Double> additionalSpread() {
      return additionalSpread;
    }

    /**
     * The meta-property for the {@code label} property.
     * @return the meta-property, not null
     */
    public MetaProperty<String> label() {
      return label;
    }

    /**
     * The meta-property for the {@code nodeDateType} property.
     * @return the meta-property, not null
     */
    public MetaProperty<NodeDateType> nodeDateType() {
      return nodeDateType;
    }

    /**
     * The meta-property for the {@code nodeDate} property.
     * @return the meta-property, not null
     */
    public MetaProperty<LocalDate> nodeDate() {
      return nodeDate;
    }

    //-----------------------------------------------------------------------
    @Override
    protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return ((XCcyIborIborSwapCurveNode) bean).getTemplate();
        case 1302780908:  // spreadKey
          return ((XCcyIborIborSwapCurveNode) bean).getSpreadKey();
        case 291232890:  // additionalSpread
          return ((XCcyIborIborSwapCurveNode) bean).getAdditionalSpread();
        case 102727412:  // label
          return ((XCcyIborIborSwapCurveNode) bean).getLabel();
        case 937712682:  // nodeDateType
          return ((XCcyIborIborSwapCurveNode) bean).getNodeDateType();
        case 1122582736:  // nodeDate
          return ((XCcyIborIborSwapCurveNode) bean).nodeDate;
      }
      return super.propertyGet(bean, propertyName, quiet);
    }

    @Override
    protected void propertySet(Bean bean, String propertyName, Object newValue, boolean quiet) {
      metaProperty(propertyName);
      if (quiet) {
        return;
      }
      throw new UnsupportedOperationException("Property cannot be written: " + propertyName);
    }

  }

  //-----------------------------------------------------------------------
  /**
   * The bean-builder for {@code XCcyIborIborSwapCurveNode}.
   */
  public static final class Builder extends DirectFieldsBeanBuilder<XCcyIborIborSwapCurveNode> {

    private XCcyIborIborSwapTemplate template;
    private ObservableKey spreadKey;
    private double additionalSpread;
    private String label;
    private NodeDateType nodeDateType;
    private LocalDate nodeDate;

    /**
     * Restricted constructor.
     */
    private Builder() {
      applyDefaults(this);
    }

    /**
     * Restricted copy constructor.
     * @param beanToCopy  the bean to copy from, not null
     */
    private Builder(XCcyIborIborSwapCurveNode beanToCopy) {
      this.template = beanToCopy.getTemplate();
      this.spreadKey = beanToCopy.getSpreadKey();
      this.additionalSpread = beanToCopy.getAdditionalSpread();
      this.label = beanToCopy.getLabel();
      this.nodeDateType = beanToCopy.getNodeDateType();
      this.nodeDate = beanToCopy.nodeDate;
    }

    //-----------------------------------------------------------------------
    @Override
    public Object get(String propertyName) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return template;
        case 1302780908:  // spreadKey
          return spreadKey;
        case 291232890:  // additionalSpread
          return additionalSpread;
        case 102727412:  // label
          return label;
        case 937712682:  // nodeDateType
          return nodeDateType;
        case 1122582736:  // nodeDate
          return nodeDate;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
    }

    @Override
    public Builder set(String propertyName, Object newValue) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          this.template = (XCcyIborIborSwapTemplate) newValue;
          break;
        case 1302780908:  // spreadKey
          this.spreadKey = (ObservableKey) newValue;
          break;
        case 291232890:  // additionalSpread
          this.additionalSpread = (Double) newValue;
          break;
        case 102727412:  // label
          this.label = (String) newValue;
          break;
        case 937712682:  // nodeDateType
          this.nodeDateType = (NodeDateType) newValue;
          break;
        case 1122582736:  // nodeDate
          this.nodeDate = (LocalDate) newValue;
          break;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
      return this;
    }

    @Override
    public Builder set(MetaProperty<?> property, Object value) {
      super.set(property, value);
      return this;
    }

    @Override
    public Builder setString(String propertyName, String value) {
      setString(meta().metaProperty(propertyName), value);
      return this;
    }

    @Override
    public Builder setString(MetaProperty<?> property, String value) {
      super.setString(property, value);
      return this;
    }

    @Override
    public Builder setAll(Map<String, ? extends Object> propertyValueMap) {
      super.setAll(propertyValueMap);
      return this;
    }

    @Override
    public XCcyIborIborSwapCurveNode build() {
      preBuild(this);
      return new XCcyIborIborSwapCurveNode(
          template,
          spreadKey,
          additionalSpread,
          label,
          nodeDateType,
          nodeDate);
    }

    //-----------------------------------------------------------------------
    /**
     * Sets the template for the swap associated with this node.
     * @param template  the new value, not null
     * @return this, for chaining, not null
     */
    public Builder template(XCcyIborIborSwapTemplate template) {
      JodaBeanUtils.notNull(template, "template");
      this.template = template;
      return this;
    }

    /**
     * Sets the key identifying the market data value which provides the spread.
     * @param spreadKey  the new value, not null
     * @return this, for chaining, not null
     */
    public Builder spreadKey(ObservableKey spreadKey) {
      JodaBeanUtils.notNull(spreadKey, "spreadKey");
      this.spreadKey = spreadKey;
      return this;
    }

    /**
     * Sets the additional spread added to the market quote.
     * @param additionalSpread  the new value
     * @return this, for chaining, not null
     */
    public Builder additionalSpread(double additionalSpread) {
      this.additionalSpread = additionalSpread;
      return this;
    }

    /**
     * Sets the label to use for the node, defaulted.
     * <p>
     * When building, this will default based on the tenor if not specified.
     * @param label  the new value, not empty
     * @return this, for chaining, not null
     */
    public Builder label(String label) {
      JodaBeanUtils.notEmpty(label, "label");
      this.label = label;
      return this;
    }

    /**
     * Sets the type of date associated to the node. Defaulted to LAST_PAYMENT_DATE.
     * @param nodeDateType  the new value
     * @return this, for chaining, not null
     */
    public Builder nodeDateType(NodeDateType nodeDateType) {
      this.nodeDateType = nodeDateType;
      return this;
    }

    /**
     * Sets the date associated to the node. Used only is the nodeDateType is FIXED_DATE. Null in other cases.
     * @param nodeDate  the new value
     * @return this, for chaining, not null
     */
    public Builder nodeDate(LocalDate nodeDate) {
      this.nodeDate = nodeDate;
      return this;
    }

    //-----------------------------------------------------------------------
    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder(224);
      buf.append("XCcyIborIborSwapCurveNode.Builder{");
      buf.append("template").append('=').append(JodaBeanUtils.toString(template)).append(',').append(' ');
      buf.append("spreadKey").append('=').append(JodaBeanUtils.toString(spreadKey)).append(',').append(' ');
      buf.append("additionalSpread").append('=').append(JodaBeanUtils.toString(additionalSpread)).append(',').append(' ');
      buf.append("label").append('=').append(JodaBeanUtils.toString(label)).append(',').append(' ');
      buf.append("nodeDateType").append('=').append(JodaBeanUtils.toString(nodeDateType)).append(',').append(' ');
      buf.append("nodeDate").append('=').append(JodaBeanUtils.toString(nodeDate));
      buf.append('}');
      return buf.toString();
    }

  }

  ///CLOVER:ON
  //-------------------------- AUTOGENERATED END --------------------------
}
